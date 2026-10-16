package backned.service;

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
}
