package backned.service.impl;

import backned.common.BusinessException;
import backned.service.StorageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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

    @Override
    public Resource load(String relativePath) {
        // resolve() 里带了越界检查，路径非法会直接抛异常
        Path path = resolve(relativePath);
        // 只返回句柄，一个字节都不读。真正的读取发生在 Spring 的
        // ResourceHttpMessageConverter 里，它用 8KB 缓冲区循环 write 到响应流，
        // 所以文件多大都不会整个进堆内存。
        return new FileSystemResource(path);
    }

    /**
     * 列出存储根目录下的全部文件，供对账巡检使用。
     * <p>
     * 三个实现细节：
     * <ul>
     *   <li>{@code Files.walk} 必须放在 try-with-resources 里：它返回的 Stream 持有目录句柄，
     *       不关闭会泄漏文件句柄（Windows 上表现为目录删不掉）。</li>
     *   <li>只保留普通文件（{@code isRegularFile}）：空目录不该被当成"神秘文件"对出来。</li>
     *   <li>相对路径要把反斜杠换成正斜杠：Windows 上 {@code relativize} 给的是
     *       {@code 2026\10\16\xxx}，而库里存的是 {@code 2026/10/16/xxx}，
     *       不统一就一个都对不上，整片磁盘会全被报成孤儿。</li>
     * </ul>
     * 默认不跟随符号链接，避免软链把目录扫成环。
     */
    @Override
    public List<StoredFile> listAll() {
        try (Stream<Path> stream = Files.walk(rootDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(this::toStoredFile)
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            // 扫描失败属于环境问题（根目录不可读），不是业务错误；但对接口来说仍是失败，
            // 交给全局异常处理变成 500，日志里保留完整堆栈。
            log.error("存储目录扫描失败: {}", rootDir, e);
            throw new BusinessException("存储目录扫描失败，请检查存储根目录是否可读");
        }
    }

    /** 读一个文件的元信息；IOException 转成非受检异常，方便在 stream 里用 */
    private StoredFile toStoredFile(Path path) {
        try {
            return new StoredFile(
                    rootDir.relativize(path).toString().replace('\\', '/'),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件元信息失败: " + path, e);
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
