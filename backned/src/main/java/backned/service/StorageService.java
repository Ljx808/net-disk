package backned.service;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 存储抽象层。
 * <p>
 * 现在只有本地磁盘一种实现，看起来多此一举，但这正是项目约定要留的扩展点：
 * 业务代码（FileService）只依赖这个接口，将来要换成 OSS / MinIO，
 * 只需要新写一个 OSSStorageServiceImpl 并把它注册成 Bean，FileService 一行都不用改。
 * <p>
 * 另一个隐性好处：写单元测试时可以用一个内存实现替换掉真实的磁盘读写。
 */
public interface StorageService {

    /**
     * 保存文件。
     * <p>
     * 为什么要传 InputStream 而不是 MultipartFile：
     * 接口不应该认识 Web 层的东西（MultipartFile 是 spring-web 的类）。
     * 保持这个接口只依赖 java.io，将来搬到别的框架或非 Web 场景都能直接用。
     *
     * @param inputStream 文件内容流，由调用方负责关闭
     * @return 相对存储根目录的路径，如 2026/10/16/ab12cd34...
     */
    String save(InputStream inputStream);

    /**
     * 删除文件。
     * Day4 只在"并发上传同一文件"的兜底分支里用到（把刚写的重复文件清掉），
     * 后面做删除/回收站功能时会复用。
     *
     * @param relativePath save() 返回的那个相对路径
     * @return 是否真的删掉了（文件本来就不存在也算成功）
     */
    boolean delete(String relativePath);

    /**
     * 打开一个已存储的文件供读取。
     * <p>
     * 为什么返回 Resource 而不是 Path 或 byte[]：
     * <ul>
     *   <li>返回 byte[]：整个文件进内存，下载大文件直接 OOM，直接排除；</li>
     *   <li>返回 Path：只对"文件在本地磁盘上"成立，换成 OSS 以后根本没有本地路径，
     *       这个抽象就废了 —— 而"能换成 OSS"正是这个接口存在的理由；</li>
     *   <li>返回 Resource：磁盘实现给 FileSystemResource，OSS 实现给 InputStreamResource，
     *       上层都能直接交给 Spring MVC 流式输出，两边都在同一个抽象下。</li>
     * </ul>
     * 注：这个接口原本只依赖 java.io，加入 Resource 后多依赖了 spring-core。
     * 这是有意的让步 —— "能读出内容"是存储抽象的核心职责，
     * 而 Resource 是磁盘和对象存储都满足的最小公共抽象。
     * <p>
     * 这里只返回句柄，不读文件内容；文件是否存在由调用方通过 {@code resource.exists()} 判断。
     *
     * @param relativePath save() 返回的那个相对路径
     */
    Resource load(String relativePath);
}
