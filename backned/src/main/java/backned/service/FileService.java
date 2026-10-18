package backned.service;

import backned.dto.CreateFolderDTO;
import backned.vo.DownloadFileVO;
import backned.vo.FileItemVO;
import backned.vo.FileUploadVO;
import backned.vo.PageVO;
import backned.vo.PurgeBatchVO;
import backned.vo.PurgeItemVO;
import backned.vo.RecycleItemVO;
import backned.vo.UploadConfigVO;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /** 上传单个文件到指定目录（parentId=0 为根目录），返回这条文件记录的信息 */
    FileUploadVO upload(MultipartFile file, Long parentId);

    /**
     * 分页查询当前登录用户指定目录（parentId=0 为根目录）下未删除的文件与文件夹。
     * 查谁的文件由 ThreadLocal 里的登录态决定，参数里没有 userId。
     * 排序为"文件夹在前，各自按创建时间倒序"。
     *
     * @param pageNum  页码，从 1 开始，非法值由实现类夹紧
     * @param pageSize 每页条数，非法值由实现类夹紧
     * @param parentId 父目录 id，null 或 0 表示根目录
     */
    PageVO<FileItemVO> listMine(long pageNum, long pageSize, Long parentId);

    /**
     * 新建文件夹。
     * <p>
     * 父目录必须存在、属于当前用户、未被删除且本身是文件夹，否则 40401；
     * 同目录下已存在同名文件或文件夹（未删除）时返回 40901，不自动改名。
     */
    FileItemVO createFolder(CreateFolderDTO dto);

    /**
     * 分页查询当前登录用户的回收站（{@code is_delete=1} 的记录）。
     * <p>
     * 不加 parent_id 条件：回收站是用户维度的，将来有目录后也不该只显示根目录的删除项。
     * 分页参数口径与 {@link #listMine} 完全一致。
     */
    PageVO<RecycleItemVO> listRecycle(long pageNum, long pageSize);

    /**
     * 软删除：把文件移入回收站。
     * <p>
     * 只改 {@code user_file.is_delete}，<b>不动</b> {@code real_file}、{@code ref_count} 和磁盘文件。
     *
     * @return true=本次真的移入；false=本来就在回收站（幂等）
     * @throws backned.common.BusinessException 40401 文件不存在或无权访问
     */
    boolean softDelete(Long userFileId);

    /**
     * 从回收站恢复文件。
     * <p>
     * 同样只改 {@code is_delete}，不碰物理文件。恢复前检查同目录有无同名未删除文件，
     * 有冲突时不自动改名，直接报错交给用户处理。
     *
     * @return true=本次真的恢复；false=本来就在列表里（幂等）
     * @throws backned.common.BusinessException 40401 文件不存在或无权访问；40901 同目录同名冲突
     */
    boolean restore(Long userFileId);

    /**
     * 彻底删除一条回收站记录：删 {@code user_file} 行、{@code ref_count - 1}，
     * 引用数归零时再删 {@code real_file} 行和物理文件。
     * <p>
     * 只接受 {@code is_delete=1} 的记录，列表态文件必须先软删除（强制两步走）；
     * 不存在 / 不是自己的 / 不在回收站，对外统一 40401，不区分。
     *
     * @throws backned.common.BusinessException 40401 文件不存在或无权访问；40402 记录在但内容缺失
     */
    PurgeItemVO purge(Long userFileId);

    /**
     * 清空当前用户的回收站。
     * <p>
     * 服务端按登录用户圈定全部 {@code is_delete=1} 的记录，逐条走与 {@link #purge} 完全相同的逻辑
     * （含越权、状态、共享、物理删除的全部复核）。单条失败不中断整批，结果逐条返回。
     */
    PurgeBatchVO purgeAllRecycle();

    /** 上传相关配置。前端启动时拿它显示"单文件最大 xxx"，避免把限制写死在页面上 */
    UploadConfigVO getUploadConfig();

    /**
     * 按 user_file 的 id 下载文件。
     * <p>
     * 归属校验在实现类里做，且写进 SQL 条件：查不到 / 不是自己的 / 已进回收站，
     * 三种情况对外都是同一个错误，不区分。
     *
     * @param userFileId user_file 主键
     * @throws backned.common.BusinessException 40401 文件不存在或无权访问；40402 记录在但磁盘文件缺失
     */
    DownloadFileVO download(Long userFileId);
}
