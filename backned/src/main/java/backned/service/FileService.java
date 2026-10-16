package backned.service;

import backned.vo.DownloadFileVO;
import backned.vo.FileItemVO;
import backned.vo.FileUploadVO;
import backned.vo.PageVO;
import backned.vo.UploadConfigVO;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /** 上传单个文件，返回这条文件记录的信息 */
    FileUploadVO upload(MultipartFile file);

    /**
     * 分页查询当前登录用户根目录（parentId=0）下未删除的文件。
     * 查谁的文件由 ThreadLocal 里的登录态决定，参数里没有 userId。
     *
     * @param pageNum  页码，从 1 开始，非法值由实现类夹紧
     * @param pageSize 每页条数，非法值由实现类夹紧
     */
    PageVO<FileItemVO> listMine(long pageNum, long pageSize);

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
