package backned.service;

import backned.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    /** 上传单个文件，返回这条文件记录的信息 */
    FileUploadVO upload(MultipartFile file);
}
