package backned.vo;

import backned.entity.RealFile;
import backned.entity.UserFile;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上传成功后返回给前端的信息。
 * <p>
 * 注意这里没有 file_path：磁盘路径属于服务端内部实现，
 * 暴露给前端既没用也不安全（前端拿着路径也没法直接访问）。
 */
@Data
public class FileUploadVO {

    /** user_file 的 id，后面做下载/删除时前端要用它 */
    private Long id;

    private String filename;

    private Long fileSize;

    private String md5;

    /**
     * true 表示磁盘上已经有这份文件了，这次只插了一条用户记录，没有重复占空间。
     * 前端拿它可以在提示语里区分一下（"已秒传"/"上传成功"）。
     */
    private Boolean reused;

    private LocalDateTime createTime;

    public static FileUploadVO of(UserFile userFile, RealFile realFile, boolean reused) {
        FileUploadVO vo = new FileUploadVO();
        vo.setId(userFile.getId());
        vo.setFilename(userFile.getFilename());
        vo.setFileSize(realFile.getFileSize());
        vo.setMd5(realFile.getMd5());
        vo.setReused(reused);
        vo.setCreateTime(userFile.getCreateTime());
        return vo;
    }
}
