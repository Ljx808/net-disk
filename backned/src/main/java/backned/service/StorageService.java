package backned.service;

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
}
