package backned.service.impl;

import backned.entity.RealFile;
import backned.mapper.RealFileMapper;
import backned.mapper.UserFileMapper;
import backned.service.ReconcileService;
import backned.service.StorageService;
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
            "口径：is_delete=1 的回收站行也计为引用（物理文件必须保留）；Day 8 设计删除时正式定义";

    private static final String CAVEAT =
            "扫描与上传并发时，刚落盘、尚未插入 real_file 的文件会短暂出现在孤儿列表；"
                    + "因此清理必须带时间条件（早于 N 天），不能只凭一次快照判断。"
                    + "本次不检查 user_file 指向不存在的 real_file 的情况，也不做任何修复。";

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
        List<RealFile> realFiles = realFileMapper.selectList(null);
        realFiles.sort(Comparator.comparing(RealFile::getId));

        // 用 Set 而不是 Map 做"库里有这个路径吗"的判断：
        // file_path 没有唯一索引，理论上可能出现两行同路径，用 Map 会吞掉一行。
        Set<String> dbPaths = realFiles.stream()
                .map(RealFile::getFilePath)
                .map(this::normalize)
                .collect(Collectors.toSet());

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
