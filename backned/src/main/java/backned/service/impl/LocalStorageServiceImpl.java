package backned.service.impl;

import backned.common.BusinessException;
import backned.service.StorageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘存储实现。
 */
@Slf4j
@Service
public class LocalStorageServiceImpl implements StorageService {

    /** 按天分目录，格式 2026/10/16 */
    private static final DateTimeFormatter DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    @Value("${net-disk.storage.root-path}")
    private String rootPath;

    private Path rootDir;

    /**
     * 启动时就把根目录建好，并打印出来。
     * 好处：① 目录不存在时不用等第一次上传才报错；② 日志里能直接看到文件存到哪了，排查问题时省事。
     */
    @PostConstruct
    public void init() {
        this.rootDir = Paths.get(rootPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("存储根目录创建失败: " + rootDir, e);
        }
        log.info("本地存储根目录: {}", rootDir);
    }

    @Override
    public String save(InputStream inputStream) {
        // 1. 生成相对路径：按日期分目录 + 32位随机文件名
        //    按天分目录是为了防止单目录下堆几十万个文件，影响文件系统性能
        String relativePath = LocalDate.now().format(DIR_FORMATTER)
                + "/"
                + UUID.randomUUID().toString().replace("-", "");

        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            // 用 Files.copy 而不是 transferTo：这个接口只依赖 java.io，不依赖 Spring 的 MultipartFile
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("文件写入失败: {}", target, e);
            throw new BusinessException("文件保存失败，请稍后重试");
        }
        return relativePath;
    }

    @Override
    public boolean delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        try {
            return Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // 删不掉不影响主流程（比如文件本来就没了），记个日志就行，不要让用户看到 500
            log.warn("物理文件删除失败: {}", relativePath, e);
            return false;
        }
    }

    /**
     * 相对路径 -> 绝对路径，并做一次越界检查。
     * <p>
     * 为什么要查：如果哪天 relativePath 能由外部输入影响（比如后面做"按路径下载"），
     * 有人传一个 "../../../../windows/system32/xxx" 就能写到存储目录外面去 —— 这就是路径穿越攻击。
     * 这里统一在出口处拦一道，安全校验只写一次。
     */
    private Path resolve(String relativePath) {
        Path path = rootDir.resolve(relativePath).normalize();
        if (!path.startsWith(rootDir)) {
            throw new BusinessException("非法的文件路径");
        }
        return path;
    }
}
