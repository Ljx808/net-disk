package backned.service.impl;

import backned.common.BusinessException;
import backned.entity.RealFile;
import backned.entity.UserFile;
import backned.mapper.RealFileMapper;
import backned.mapper.UserFileMapper;
import backned.service.FileService;
import backned.service.StorageService;
import backned.utils.UserContext;
import backned.vo.DownloadFileVO;
import backned.vo.FileItemVO;
import backned.vo.FileUploadVO;
import backned.vo.PageVO;
import backned.vo.RecycleItemVO;
import backned.vo.UploadConfigVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文件业务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 单页最大条数。pageSize 是前端传的，能传多大由服务端说了算 */
    private static final long MAX_PAGE_SIZE = 100L;

    private final RealFileMapper realFileMapper;
    private final UserFileMapper userFileMapper;
    private final StorageService storageService;

    /**
     * 单文件大小上限，直接读 multipart 配置。
     * "100MB" 这种带单位的字符串 Spring 会自动转成 DataSize，不用自己解析。
     * 这样 yml 改了配置，这里的值和 GlobalExceptionHandler 的提示语会一起变。
     */
    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    /**
     * 上传流程：算 md5 → 找物理文件 → 决定要不要落盘 → 插用户记录。
     * <p>
     * 关于事务：这个方法故意没有加 @Transactional，原因有两个，面试常问：
     * ① 磁盘写入根本不在事务管辖范围内，加事务也回滚不掉已经落盘的文件，只是"看起来安全"；
     * ② 下面要捕获 DuplicateKeyException 然后继续查库。在同一个事务里捕获数据库异常后继续操作，
     *    事务已被标记为 rollback-only，最后提交时会抛 UnexpectedRollbackException，反而更糟。
     */
    @Override
    public FileUploadVO upload(MultipartFile file) {
        // 1. 用户身份只认 ThreadLocal 里拦截器解析出来的值。
        //    接口签名里没有 userId 参数，前端就算多传一个 userId 也进不来，从根上避免了越权上传。
        Long userId = requireUserId();

        if (file == null || file.isEmpty()) {
            // 400 而不是默认的 500：这是调用方传错了，不是服务端故障
            throw new BusinessException(400, "上传文件不能为空");
        }

        String filename = sanitizeFilename(file.getOriginalFilename());

        // 2. 算 md5。用流式算法而不是 file.getBytes()：
        //    getBytes() 会把整个文件读进内存，上传 100MB 就吃 100MB 堆内存，几个人一起传直接把服务打挂
        String md5;
        try {
            md5 = DigestUtils.md5DigestAsHex(file.getInputStream());
        } catch (IOException e) {
            log.error("计算文件 md5 失败", e);
            throw new BusinessException("文件读取失败，请重试");
        }

        // 3. 先看这个内容是不是已经在磁盘上了
        RealFile realFile = selectByMd5(md5);
        boolean reused = realFile != null;

        if (!reused) {
            // 3.1 新内容：先落盘，再插记录
            realFile = saveNewRealFile(file, md5, filename);
        }

        // 4. 插一条属于当前用户的记录。同一个文件被同一个用户传两次，就会有两条记录，
        //    这符合网盘的行为（用户可以有两份同名文件），磁盘上仍然只有一份。
        UserFile userFile = new UserFile();
        userFile.setUserId(userId);
        userFile.setRealFileId(realFile.getId());
        userFile.setFilename(filename);
        userFile.setParentId(0L);
        userFile.setIsDelete(0);
        // 时间字段在代码里显式赋值，而不是依赖 MySQL 的 DEFAULT CURRENT_TIMESTAMP：
        // MyBatis-Plus 插入后不会把数据库生成的列值回读进实体，
        // 不显式赋值的话下面 toVO 拿到的 createTime 就是 null。
        // 代价是时间由应用机器决定而不是数据库机器，本项目两者是同一台所以无所谓；
        // 项目再大一点，标准做法是实现 MyBatis-Plus 的 MetaObjectHandler 统一自动填充。
        LocalDateTime now = LocalDateTime.now();
        userFile.setCreateTime(now);
        userFile.setUpdateTime(now);
        userFileMapper.insert(userFile);

        // 5. 引用计数 +1。用 SQL 自增而不是"先查出来再 set"，避免并发下算丢
        realFileMapper.update(null, new LambdaUpdateWrapper<RealFile>()
                .setSql("ref_count = ref_count + 1")
                .eq(RealFile::getId, realFile.getId()));

        log.info("上传成功 userId={}, fileId={}, filename={}, size={}, reused={}",
                userId, userFile.getId(), filename, realFile.getFileSize(), reused);

        return FileUploadVO.of(userFile, realFile, reused);
    }

    /**
     * 分页查询当前用户的文件列表。
     * <p>
     * 用两次查询而不是写 join：
     * ① 不需要新增 mapper XML 或 @Select 自定义 SQL，插件分页直接就能用；
     * ② 一页最多 100 条，第二次是一次 IN (100 个主键) 的查询，成本可忽略；
     * 代价是两次查询之间如果有人删了 real_file，第二次会取不到（本项目没有删除物理文件的入口，
     * 代码里也做了防御，取不到就给 0 而不是让整个列表报 500）。
     */
    @Override
    public PageVO<FileItemVO> listMine(long pageNum, long pageSize) {
        Long userId = requireUserId();

        // 夹紧分页参数。pageSize 不夹紧的话，前端传个 100000 就能把整张表拉出来，
        // 这是个很常见的低级漏洞（接口防刷的基本功）。
        long safePageNum = Math.max(pageNum, 1L);
        long safePageSize = Math.min(Math.max(pageSize, 1L), MAX_PAGE_SIZE);

        Page<UserFile> page = new Page<>(safePageNum, safePageSize);

        // user_id 是查询条件的一部分，而不是"查出来再过滤"：
        // 越权这道防线必须在 SQL 层面就堵死，前端传什么 userId 都进不来
        LambdaQueryWrapper<UserFile> wrapper = new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getParentId, 0L)   // Day5 只有根目录，目录结构是后续的事
                .eq(UserFile::getIsDelete, 0)    // 回收站里的记录不出现
                .orderByDesc(UserFile::getCreateTime)
                // 按时间排序一定要带上 id 兜底：同一秒上传的多个文件 create_time 完全相同，
                // MySQL 对"排序值相同的行"顺序不保证稳定，翻页时会出现同一行重复出现或漏掉
                .orderByDesc(UserFile::getId);
        userFileMapper.selectPage(page, wrapper);

        Map<Long, RealFile> realFileMap = loadRealFileMap(page.getRecords());

        return PageVO.of(page, userFile -> {
            RealFile realFile = realFileMap.get(userFile.getRealFileId());
            FileItemVO vo = new FileItemVO();
            vo.setId(userFile.getId());
            vo.setFilename(userFile.getFilename());
            vo.setFileSize(realFile == null ? 0L : realFile.getFileSize());
            vo.setCreateTime(userFile.getCreateTime());
            return vo;
        });
    }

    /**
     * 回收站列表。
     * <p>
     * 与文件列表的隔离只有一处：这里查 is_delete=1，文件列表查 is_delete=0。
     * 两条 SQL 都带 user_id，跨用户的行在任何一条里都读不到。
     */
    @Override
    public PageVO<RecycleItemVO> listRecycle(long pageNum, long pageSize) {
        Long userId = requireUserId();

        long safePageNum = Math.max(pageNum, 1L);
        long safePageSize = Math.min(Math.max(pageSize, 1L), MAX_PAGE_SIZE);
        Page<UserFile> page = new Page<>(safePageNum, safePageSize);

        // 故意不加 parent_id 条件：回收站是用户维度的，将来有目录后也不该只显示根目录的删除项。
        // 索引代价：idx_user_parent(user_id, parent_id, is_delete) 只能用到最左列 user_id，
        // is_delete 在第三列、跳过 parent_id 无法作为索引查找条件；当前数据量无感，已记入技术债观察。
        userFileMapper.selectPage(page, new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getIsDelete, 1)
                // update_time 在软删除那一刻被 ON UPDATE CURRENT_TIMESTAMP 刷新，等价于"删除时间"；
                // 同样带 id 兜底，保证同一秒删除的多条记录翻页顺序稳定
                .orderByDesc(UserFile::getUpdateTime)
                .orderByDesc(UserFile::getId));

        Map<Long, RealFile> realFileMap = loadRealFileMap(page.getRecords());

        return PageVO.of(page, userFile -> {
            RealFile realFile = realFileMap.get(userFile.getRealFileId());
            RecycleItemVO vo = new RecycleItemVO();
            vo.setId(userFile.getId());
            vo.setFilename(userFile.getFilename());
            // real_file 理论上一定在；真被改坏了也给 0，不让整个回收站列表报 500
            vo.setFileSize(realFile == null ? 0L : realFile.getFileSize());
            vo.setDeleteTime(userFile.getUpdateTime());
            return vo;
        });
    }

    /**
     * 软删除。
     * <p>
     * 三件事严格不做：不删 user_file 行、不动 real_file.ref_count、不删磁盘文件。
     * 回收站里的记录仍然是一条完整的引用，物理文件必须留在原地，否则"恢复"就会恢复出
     * 一条下载 404 的死记录。这个语义已写入 CLAUDE.md。
     */
    @Override
    public boolean softDelete(Long userFileId) {
        Long userId = requireUserId();

        // 先按 id + user_id 查，故意不加 is_delete 条件：
        // 需要区分"自己的、已在回收站"（幂等成功）和"不存在 / 不是自己的"（404）。
        // user_id 写进 WHERE，越权在 SQL 层就堵死。
        UserFile userFile = userFileMapper.selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, userFileId)
                .eq(UserFile::getUserId, userId));
        if (userFile == null) {
            // 不存在 / 别人的，统一 40401 + 统一文案，不给 403，防 id 枚举
            throw new BusinessException(40401, "文件不存在或无权访问");
        }
        if (Integer.valueOf(1).equals(userFile.getIsDelete())) {
            return false; // 幂等：本来就在回收站
        }

        // 条件里带 is_delete=0，防并发重复删除。
        // 不显式 set update_time，让 MySQL 的 ON UPDATE CURRENT_TIMESTAMP 把它刷成删除时间。
        int updated = userFileMapper.update(null, new LambdaUpdateWrapper<UserFile>()
                .eq(UserFile::getId, userFileId)
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getIsDelete, 0)
                .set(UserFile::getIsDelete, 1));
        if (updated == 0) {
            // 并发窗口里别的请求先删了：重读一次，仍然只按归属说话
            UserFile latest = userFileMapper.selectById(userFileId);
            if (latest == null || !userId.equals(latest.getUserId())) {
                throw new BusinessException(40401, "文件不存在或无权访问");
            }
            return false;
        }

        log.info("文件移入回收站 userId={}, userFileId={}, filename={}",
                userId, userFileId, userFile.getFilename());
        return true;
    }

    /**
     * 从回收站恢复。
     * <p>
     * 用事务把"冲突检查 + 状态更新"包在一起：两步之间若失败，不能留下"检查过了但没更新"的中间态。
     * 注意库里没有 (user_id, parent_id, filename) 唯一索引，事务也挡不住并发插入造成的极小窗口，
     * 彻底解决需要加唯一索引，属于要 ALTER 的独立改动，本轮不做。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean restore(Long userFileId) {
        Long userId = requireUserId();

        UserFile userFile = userFileMapper.selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, userFileId)
                .eq(UserFile::getUserId, userId));
        if (userFile == null) {
            throw new BusinessException(40401, "文件不存在或无权访问");
        }
        if (Integer.valueOf(0).equals(userFile.getIsDelete())) {
            return false; // 幂等：本来就在文件列表里
        }

        // 同 user、同目录、同名、未删除、且不是自己 → 冲突。
        // filename 列是 utf8mb4_unicode_ci，等值比较大小写不敏感，与数据库的语义保持一致。
        Long conflict = userFileMapper.selectCount(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getParentId, userFile.getParentId())
                .eq(UserFile::getFilename, userFile.getFilename())
                .eq(UserFile::getIsDelete, 0)
                .ne(UserFile::getId, userFile.getId()));
        if (conflict != null && conflict > 0) {
            // 不做自动改名：改名功能还没建，自动加 "(1)" 会让用户看到一条意料之外的文件名。
            // 40901 → 真正的 HTTP 409（GlobalExceptionHandler 的 409xx 区间映射）
            throw new BusinessException(40901,
                    "同目录下已存在同名文件「" + userFile.getFilename() + "」，请先重命名或删除后再恢复");
        }

        int updated = userFileMapper.update(null, new LambdaUpdateWrapper<UserFile>()
                .eq(UserFile::getId, userFile.getId())
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getIsDelete, 1)
                .set(UserFile::getIsDelete, 0));
        if (updated == 0) {
            // 并发：可能已被另一个请求恢复。重读后按当前状态回答，结果一致就当成功
            UserFile latest = userFileMapper.selectById(userFile.getId());
            if (latest == null || !userId.equals(latest.getUserId())) {
                throw new BusinessException(40401, "文件不存在或无权访问");
            }
            if (Integer.valueOf(0).equals(latest.getIsDelete())) {
                return false;
            }
            throw new BusinessException("恢复失败，请稍后重试");
        }

        log.info("从回收站恢复 userId={}, userFileId={}, filename={}",
                userId, userFile.getId(), userFile.getFilename());
        return true;
    }

    @Override
    public UploadConfigVO getUploadConfig() {
        UploadConfigVO vo = new UploadConfigVO();
        vo.setMaxFileSize(maxFileSize.toBytes());
        return vo;
    }

    /**
     * 下载：校验归属 → 定位物理文件 → 把句柄交给 controller 去流式输出。
     * <p>
     * 这个方法里一个字节的文件内容都没读，读的动作发生在 controller 返回后的
     * 消息转换阶段。所以它执行得很快，不会因为文件大而占内存或长时间持有连接。
     */
    @Override
    public DownloadFileVO download(Long userFileId) {
        Long userId = requireUserId();

        // 归属校验：user_id 和 is_delete 都写进 WHERE。
        // "记录不存在"、"是别人的"、"已进回收站"三种情况在 SQL 层面就是同一个结果 —— 查不到，
        // 所以代码里只有一个分支，也就不存在"忘了比对 userId"这种低级错误的可能。
        UserFile userFile = userFileMapper.selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getId, userFileId)
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getIsDelete, 0));
        if (userFile == null) {
            // 三种情况统一 40401、统一文案，故意不做区分：
            // 如果"不是你的"返回 403、"不存在"返回 404，攻击者拿状态码差异就能
            // 探测出某个 id 是否存在（文件存在性泄露），能遍历出别人有哪些文件
            throw new BusinessException(40401, "文件不存在或无权访问");
        }

        RealFile realFile = realFileMapper.selectById(userFile.getRealFileId());
        if (realFile == null) {
            // 数据不一致：user_file 一定指向一条 real_file 记录，指向的没了说明库被改坏了。
            // 这是要人处理的问题，所以记 error；但对外文案保持中性，不把内部结构暴露给用户
            log.error("数据不一致: user_file 指向的 real_file 不存在. userFileId={}, realFileId={}",
                    userFile.getId(), userFile.getRealFileId());
            throw new BusinessException(40402, "文件内容缺失，请重新上传");
        }

        // 只拿句柄，不读内容；文件在不在由 exists() 判断
        Resource resource = storageService.load(realFile.getFilePath());
        if (!resource.exists()) {
            // 另一种数据不一致：记录在、磁盘上没有（手工删了文件、磁盘挂了、迁移漏了）。
            // 同样记 error 但对外 40402 —— 绝不能让它冒成 500，
            // 500 的意思是"服务端出故障了"，而这个用户的文件本身也确实是拿不到了
            log.error("数据不一致: 磁盘上的物理文件缺失. realFileId={}, path={}",
                    realFile.getId(), realFile.getFilePath());
            throw new BusinessException(40402, "文件内容缺失，请重新上传");
        }

        DownloadFileVO vo = new DownloadFileVO();
        // 下载时给用户的名字用 user_file.filename（他当初上传时的名字），
        // 不是磁盘上那个 UUID 文件名 —— 后者是服务端的内部实现
        vo.setFilename(userFile.getFilename());
        vo.setMimeType(realFile.getMimeType());
        vo.setResource(resource);
        return vo;
    }

    /**
     * 取当前登录用户；取不到说明 JWT 拦截器没把身份放进 ThreadLocal。
     * <p>
     * 拦截器正常工作时这个分支不会走到，保留它是纵深防御：
     * 万一将来某个接口被加进放行名单，也不会以 userId=null 去查库。
     */
    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }

    /**
     * 一次批量取回本页涉及的 real_file，避免 N+1 查询。
     * <p>
     * 文件列表和回收站共用。空集合不能直接丢给 selectByIds：
     * 那会拼出 {@code where id in ()} 这种语法错误的 SQL。
     */
    private Map<Long, RealFile> loadRealFileMap(List<UserFile> records) {
        List<Long> realFileIds = records.stream()
                .map(UserFile::getRealFileId)
                .distinct()
                .toList();
        return realFileIds.isEmpty()
                ? Map.of()
                : realFileMapper.selectByIds(realFileIds).stream()
                        .collect(Collectors.toMap(RealFile::getId, Function.identity()));
    }

    /**
     * 落盘 + 插入 real_file 记录。
     * <p>
     * 顺序是"先写磁盘，再写数据库"：反过来的话，数据库插入成功但磁盘写失败，
     * 就会留下一条指向不存在文件的记录，用户点了下载直接报错；
     * 而按这个顺序出问题最多是磁盘上多一个没人引用的孤儿文件，不影响使用。
     */
    private RealFile saveNewRealFile(MultipartFile file, String md5, String filename) {
        String relativePath;
        // MultipartFile.getInputStream() 每次调用都会返回一个新的流，所以这里可以重新读一遍
        try (InputStream in = file.getInputStream()) {
            relativePath = storageService.save(in);
        } catch (IOException e) {
            log.error("文件写入磁盘失败, filename={}", filename, e);
            throw new BusinessException("文件保存失败，请稍后重试");
        }

        RealFile realFile = new RealFile();
        realFile.setMd5(md5);
        realFile.setFilePath(relativePath);
        realFile.setFileSize(file.getSize());
        realFile.setMimeType(file.getContentType());
        realFile.setRefCount(0);
        realFile.setCreateTime(LocalDateTime.now());

        try {
            realFileMapper.insert(realFile);
            return realFile;
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个请求同时上传同一个文件内容，都发现"库里没有"，都写了盘。
            // 唯一索引 uk_md5 只允许一条插入成功，失败的这一方走这里：
            // 把自己刚写的那份文件删掉，改用数据库里已有的那条记录。
            log.warn("并发上传同一文件，复用已有记录. md5={}", md5);
            storageService.delete(relativePath);
            RealFile existing = selectByMd5(md5);
            if (existing == null) {
                // 理论上不会发生，真出现了说明 md5 查重和唯一索引对不上，宁可报错也不要瞎猜
                throw new BusinessException("文件保存失败，请重试");
            }
            return existing;
        }
    }

    private RealFile selectByMd5(String md5) {
        return realFileMapper.selectOne(new LambdaQueryWrapper<RealFile>().eq(RealFile::getMd5, md5));
    }

    /**
     * 清洗原始文件名。
     * <p>
     * 这个方法的返回值只用来存数据库、展示给用户、拼进下载响应头，永远不会拼成磁盘路径
     * （磁盘文件名是服务端生成的 UUID），所以不用怕 "../" 这种路径穿越。
     * 但还是要处理四件事：可能带控制字符、可能带完整路径、可能为空、可能超长。
     */
    private String sanitizeFilename(String original) {
        if (original == null) {
            return "unnamed";
        }

        // 1. 先剥掉控制字符（\r \n \t 以及各种不可见字符）。
        //    这一步是 Day6 加的：文件名现在会被拼进下载响应头的 Content-Disposition，
        //    留着 \r\n 的话，攻击者把文件名取成 "a.txt\r\nX-Injected: 1" 就能往响应里
        //    注入额外的头（响应头注入 / HTTP 响应拆分）。
        //    下载接口拼头之前还会再剥一遍 —— 两边都做，因为库里可能还留着
        //    这道过滤加上之前存进来的存量数据，光靠入库时消毒救不了它们。
        String name = original.replaceAll("\\p{Cntrl}", "");

        // 2. 有些客户端（尤其是老 IE / 某些 SDK）会把整个路径塞进 originalFilename
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();

        // 3. 剥完控制字符后名字可能变空（比如原本就是 "\r\n"），所以判空放在剥完之后
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        // 4. 数据库字段是 varchar(255)，按字符截断留点余量，避免超出长度导致插入失败
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }
}
