package backned.service.impl;

import backned.entity.RealFile;
import backned.mapper.RealFileMapper;
import backned.mapper.UserFileMapper;
import backned.service.ReconcileService;
import backned.service.StorageService;
import backned.vo.CleanupReportVO;
import backned.vo.ReconcileReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 对账巡检实现。
 * <p>
 * 核心思路：把"磁盘上实际有什么"和"数据库说应该有什么"各自转成一个
 * <b>相对路径集合</b>，然后做两次差集。两边用同一个键是全部正确性的前提。
 * <p>
 * 只读承诺：本类里只有 selectList / 聚合查询 / 目录扫描三类操作，
 * 不调用 save、delete、update，也不删除任何文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcileServiceImpl implements ReconcileService {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String REF_COUNT_NOTE =
            "口径：is_delete=1 的回收站行也计为引用（物理文件必须保留）；该口径已于 Day 8 定案";

    private static final String CAVEAT =
            "扫描与上传并发时，刚落盘、尚未插入 real_file 的文件会短暂出现在孤儿列表；"
                    + "因此清理必须带时间条件（早于 N 天），不能只凭一次快照判断。"
                    + "本次不检查 user_file 指向不存在的 real_file 的情况，也不做任何修复。";

    /**
     * 清理的最小落盘年龄。
     * <p>
     * 24 小时不是随便定的：上传是"先落盘、再插库"，两步之间有一个极短窗口；
     * 如果对账恰好落在这个窗口里，这个文件看起来就是孤儿。
     * 24 小时远大于任何正常上传耗时，能把这类误判全部挡在外面。
     */
    private static final Duration CLEANUP_MIN_AGE = Duration.ofHours(24);

    private final StorageService storageService;
    private final RealFileMapper realFileMapper;
    private final UserFileMapper userFileMapper;

    /** 仅用于在报告里写明"扫的是哪个目录"，不参与路径拼接（拼接在存储层完成） */
    @Value("${net-disk.storage.root-path}")
    private String storageRoot;

    @Override
    public ReconcileReportVO reconcile() {
        Instant now = Instant.now();

        // ---------- 1. 盘侧：相对路径 -> 文件元信息（TreeMap 保证输出按路径升序、可重复对比） ----------
        List<StorageService.StoredFile> diskFiles = storageService.listAll();
        Map<String, StorageService.StoredFile> diskByPath = diskFiles.stream()
                .collect(Collectors.toMap(
                        StorageService.StoredFile::relativePath,
                        Function.identity(),
                        (first, duplicate) -> first,
                        TreeMap::new));

        // ---------- 2. 库侧：按 id 排好序，后面两段都按这个顺序输出 ----------
        List<RealFile> realFiles = loadRealFilesById();
        Set<String> dbPaths = toPathSet(realFiles);

        // ---------- 3. 盘有库无 ----------
        List<ReconcileReportVO.Orphan> orphans = diskByPath.entrySet().stream()
                .filter(entry -> !dbPaths.contains(entry.getKey()))
                .map(entry -> {
                    StorageService.StoredFile file = entry.getValue();
                    return ReconcileReportVO.Orphan.builder()
                            .relativePath(file.relativePath())
                            .sizeBytes(file.sizeBytes())
                            .lastModified(TIME_FORMATTER.format(file.lastModified()))
                            .ageDays(Duration.between(file.lastModified(), now).toDays())
                            .build();
                })
                .toList();

        // ---------- 4. 库有盘无 ----------
        List<ReconcileReportVO.Missing> missing = new ArrayList<>();
        for (RealFile realFile : realFiles) {
            if (!diskByPath.containsKey(normalize(realFile.getFilePath()))) {
                missing.add(ReconcileReportVO.Missing.builder()
                        .realFileId(realFile.getId())
                        .filePath(realFile.getFilePath())
                        .build());
            }
        }

        // ---------- 5. ref_count 审计 ----------
        // 一次聚合查询拿到每个 real_file 的"实际引用数"，避免 N+1
        Map<Long, Long> actualRefCounts = new HashMap<>();
        for (Map<String, Object> row : userFileMapper.countByRealFileId()) {
            Object id = row.get("realFileId");
            Object count = row.get("cnt");
            if (id instanceof Number idValue && count instanceof Number countValue) {
                actualRefCounts.put(idValue.longValue(), countValue.longValue());
            }
        }

        List<ReconcileReportVO.RefCountDiff> refCountDiffs = new ArrayList<>();
        for (RealFile realFile : realFiles) {
            long actual = actualRefCounts.getOrDefault(realFile.getId(), 0L);
            int stored = realFile.getRefCount() == null ? 0 : realFile.getRefCount();
            if (actual != stored) {
                refCountDiffs.add(ReconcileReportVO.RefCountDiff.builder()
                        .realFileId(realFile.getId())
                        .filePath(realFile.getFilePath())
                        .dbRefCount(stored)
                        .actualRefCount(actual)
                        .build());
            }
        }

        log.info("对账巡检完成: disk={}, realFile={}, orphan={}, missing={}, refCountMismatch={}",
                diskFiles.size(), realFiles.size(), orphans.size(), missing.size(), refCountDiffs.size());

        return ReconcileReportVO.builder()
                .scannedAt(TIME_FORMATTER.format(now))
                .storageRoot(storageRoot)
                .diskFileCount(diskFiles.size())
                .realFileCount(realFiles.size())
                .summary(ReconcileReportVO.Summary.builder()
                        .orphanCount(orphans.size())
                        .missingCount(missing.size())
                        .refCountMismatchCount(refCountDiffs.size())
                        .refCountChecked(realFiles.size())
                        .build())
                .orphans(orphans)
                .missing(missing)
                .refCountDiffs(refCountDiffs)
                .refCountNote(REF_COUNT_NOTE)
                .caveat(CAVEAT)
                .build();
    }

    @Override
    public CleanupReportVO cleanup(List<String> requestedPaths) {
        Instant now = Instant.now();
        List<String> paths = requestedPaths == null ? List.of() : requestedPaths;

        // ---------- 闸①的白名单：每个请求实时重算，绝不复用缓存或上一份巡检报告 ----------
        List<StorageService.StoredFile> diskFiles = storageService.listAll();
        Set<String> dbPaths = toPathSet(loadRealFilesById());
        // 只保留"盘上有、库里没有"的路径。请求里给别的路径，在下面 remove 时取不到，直接拒掉。
        Map<String, StorageService.StoredFile> orphanCandidates = diskFiles.stream()
                .filter(file -> !dbPaths.contains(file.relativePath()))
                .collect(Collectors.toMap(
                        StorageService.StoredFile::relativePath,
                        Function.identity(),
                        (first, duplicate) -> first));

        // ---------- 闸②需要的引用数：一次聚合查完，避免 N+1 ----------
        List<String> nonBlankPaths = paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        Map<String, Long> referenceCounts = new HashMap<>();
        if (!nonBlankPaths.isEmpty()) {
            for (Map<String, Object> row : userFileMapper.countReferencesByPaths(nonBlankPaths)) {
                Object filePath = row.get("filePath");
                Object count = row.get("cnt");
                if (filePath instanceof String path && count instanceof Number countValue) {
                    referenceCounts.put(path, countValue.longValue());
                }
            }
        }

        List<CleanupReportVO.Item> items = new ArrayList<>();
        int deleted = 0;

        for (String path : paths) {
            if (path == null || path.isBlank()) {
                items.add(skipped(path, "路径为空"));
                continue;
            }

            // 闸①：从实时候选集里"取走"。取不到 = 不是孤儿（或已被清理过）。
            // 这个 remove 同时让同一请求里的重复路径在第二次自动被拒，天然幂等。
            StorageService.StoredFile candidate = orphanCandidates.remove(path);
            if (candidate == null) {
                items.add(skipped(path, "不在实时孤儿候选集内（可能已被清理，或仍被 real_file 记录引用）"));
                continue;
            }

            // 闸②：任何 user_file 引用（含回收站行）都不允许删
            long references = referenceCounts.getOrDefault(path, 0L);
            if (references > 0) {
                items.add(skipped(path, "仍被 " + references + " 条 user_file 引用（含回收站行），拒绝删除"));
                continue;
            }

            // 闸③：落盘满 24 小时才允许删，挡住"先落盘、再插库"窗口里的上传中文件
            Duration age = Duration.between(candidate.lastModified(), now);
            long ageHours = age.toHours();
            if (age.compareTo(CLEANUP_MIN_AGE) < 0) {
                items.add(CleanupReportVO.Item.builder()
                        .relativePath(path)
                        .status("skipped")
                        .reason("落盘未满 24 小时（当前 " + ageHours + " 小时），拒绝删除")
                        .sizeBytes(candidate.sizeBytes())
                        .lastModified(TIME_FORMATTER.format(candidate.lastModified()))
                        .ageHours(ageHours)
                        .build());
                continue;
            }

            // 三道闸全过：删除物理文件。
            // delete() 内部还有一次"路径必须在根目录下"的越界检查，和闸①形成双保险。
            boolean removed = storageService.delete(path);
            if (!removed) {
                items.add(skipped(path, "物理文件已不存在（可能被并发清理）"));
                continue;
            }

            log.info("清理孤儿文件: path={}, size={}, lastModified={}",
                    path, candidate.sizeBytes(), TIME_FORMATTER.format(candidate.lastModified()));
            deleted++;
            items.add(CleanupReportVO.Item.builder()
                    .relativePath(path)
                    .status("deleted")
                    .reason(null)
                    .sizeBytes(candidate.sizeBytes())
                    .lastModified(TIME_FORMATTER.format(candidate.lastModified()))
                    .ageHours(ageHours)
                    .build());
        }

        log.info("孤儿清理完成: requested={}, deleted={}, skipped={}",
                paths.size(), deleted, paths.size() - deleted);

        return CleanupReportVO.builder()
                .cleanedAt(TIME_FORMATTER.format(now))
                .requested(paths.size())
                .deletedCount(deleted)
                .skippedCount(paths.size() - deleted)
                .items(items)
                .build();
    }

    /** 跳过项：只有路径和原因，没有删除元信息 */
    private CleanupReportVO.Item skipped(String path, String reason) {
        return CleanupReportVO.Item.builder()
                .relativePath(path)
                .status("skipped")
                .reason(reason)
                .build();
    }

    /** 查全部 real_file 并按 id 排序，保证报告输出顺序稳定 */
    private List<RealFile> loadRealFilesById() {
        List<RealFile> realFiles = realFileMapper.selectList(null);
        realFiles.sort(Comparator.comparing(RealFile::getId));
        return realFiles;
    }

    /**
     * 库侧路径集合。
     * <p>
     * 用 Set 而不是 Map：file_path 没有唯一索引，理论上可能出现两行同路径，用 Map 会吞掉一行。
     */
    private Set<String> toPathSet(List<RealFile> realFiles) {
        return realFiles.stream()
                .map(RealFile::getFilePath)
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    /**
     * 统一比对键：正斜杠分隔。
     * <p>
     * 磁盘侧在存储实现里已经换过了，这里再对库侧做一次，属于防御性处理：
     * 万一哪天有数据是从 Windows 拼接进来的（存了反斜杠），也不会整片对不上。
     */
    private String normalize(String filePath) {
        return filePath == null ? "" : filePath.replace('\\', '/');
    }
}
