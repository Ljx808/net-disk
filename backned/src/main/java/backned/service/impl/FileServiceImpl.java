package backned.service.impl;

import backned.common.BusinessException;
import backned.entity.RealFile;
import backned.entity.UserFile;
import backned.mapper.RealFileMapper;
import backned.mapper.UserFileMapper;
import backned.service.FileService;
import backned.service.StorageService;
import backned.utils.UserContext;
import backned.vo.FileItemVO;
import backned.vo.FileUploadVO;
import backned.vo.PageVO;
import backned.vo.UploadConfigVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
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
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

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
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

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

        List<UserFile> records = page.getRecords();

        // 空集合不能直接丢给 selectByIds：那会拼出 "where id in ()" 这种语法错误的 SQL。
        // 列表本来就空的时候直接返回，连库都不用查
        List<Long> realFileIds = records.stream()
                .map(UserFile::getRealFileId)
                .distinct()
                .toList();
        Map<Long, RealFile> realFileMap = realFileIds.isEmpty()
                ? Map.of()
                : realFileMapper.selectByIds(realFileIds).stream()
                        .collect(Collectors.toMap(RealFile::getId, Function.identity()));

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

    @Override
    public UploadConfigVO getUploadConfig() {
        UploadConfigVO vo = new UploadConfigVO();
        vo.setMaxFileSize(maxFileSize.toBytes());
        return vo;
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
     * 这个方法的返回值只用来存数据库、展示给用户，永远不会拼成磁盘路径
     * （磁盘文件名是服务端生成的 UUID），所以不用怕 "../" 这种路径穿越。
     * 但还是要处理三件事：客户端可能传完整路径、可能传空、可能超长。
     */
    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed";
        }
        // 有些客户端（尤其是老 IE / 某些 SDK）会把整个路径塞进 originalFilename
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        // 数据库字段是 varchar(255)，按字符截断留点余量，避免超出长度导致插入失败
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }
}
