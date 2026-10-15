package backned.utils;

/**
 * 当前登录用户上下文。
 * <p>
 * 为什么用 ThreadLocal：一次 HTTP 请求全程由同一个线程处理，
 * 拦截器把 userId 放进去，后面 controller/service 直接取，不用一层层传参数。
 * <p>
 * 必须清理！Tomcat 的线程是复用的，不清理的话下一个请求会读到上一个请求的用户，
 * 造成严重的"越权"事故（A 看到 B 的数据）。拦截器 afterCompletion 里已经处理。
 * <p>
 * 提示：如果以后用到 @Async 或线程池，ThreadLocal 不会自动传递到子线程，需要手动透传。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
