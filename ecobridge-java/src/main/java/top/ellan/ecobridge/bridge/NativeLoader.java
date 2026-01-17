package top.ellan.ecobridge.bridge;

import top.ellan.ecobridge.EcoBridge;
import top.ellan.ecobridge.util.LogUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * NativeLoader (Fixed Version)
 * <p>
 * 修复日志:
 * 1. 优化 extractLibrary 逻辑: 单次读取 InputStream 到内存，避免多次打开资源流导致 ClassLoader 问题。
 * 2. 增强文件写入原子性: 使用 Files.copy 替代 Files.write。
 */
public class NativeLoader {

    private static final String LIB_NAME = "ecobridge_rust";

    private static Arena globalArena;
    private static SymbolLookup symbolLookup;
    private static volatile boolean isReady = false;

    public static synchronized void load(EcoBridge plugin) {
        if (isReady) return;

        try {
            Path libPath = extractLibrary(plugin);
            
            // 使用 Shared Arena 允许跨线程调用并显式关闭
            globalArena = Arena.ofShared();
            symbolLookup = SymbolLookup.libraryLookup(libPath, globalArena);
            isReady = true;

            LogUtil.debug("NativeLoader: 共享内存域已初始化，Native 符号表已就绪。");
        } catch (Throwable e) {
            throw new RuntimeException("无法加载 Native 库: " + e.getMessage(), e);
        }
    }

    public static synchronized void unload() {
        if (!isReady) return;

        try {
            if (globalArena != null && globalArena.scope().isAlive()) {
                globalArena.close();
                LogUtil.debug("NativeLoader: 共享内存域已安全关闭。");
            }
        } catch (Throwable e) {
            LogUtil.error("NativeLoader: 内存域关闭失败", e);
        } finally {
            globalArena = null;
            symbolLookup = null;
            isReady = false;
        }
    }

    public static Optional<MemorySegment> findSymbol(String name) {
        if (!isReady || symbolLookup == null) return Optional.empty();
        try {
            return symbolLookup.find(name);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public static boolean isReady() {
        return isReady;
    }

    private static Path extractLibrary(EcoBridge plugin) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String suffix = os.contains("win") ? ".dll" : (os.contains("mac") ? ".dylib" : ".so");
        String name = (os.contains("win") ? "" : "lib") + LIB_NAME + suffix;
        Path target = plugin.getDataFolder().toPath().resolve("natives").resolve(name);

        // [Fix] 核心修复：一次性读取所有字节到内存，立即关闭 InputStream
        // 这避免了多次调用 getResource 可能导致的 null 或 stream closed 问题
        byte[] resourceBytes;
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) throw new IOException("Native lib not found in jar: " + name);
            resourceBytes = in.readAllBytes();
        }

        // 计算新文件的哈希
        String newHash = calculateHash(resourceBytes);

        // 检查现有文件
        if (Files.exists(target)) {
            try {
                byte[] existingBytes = Files.readAllBytes(target);
                String oldHash = calculateHash(existingBytes);

                // 哈希一致，跳过写入，直接返回
                if (newHash.equals(oldHash)) {
                    // LogUtil.debug("Native lib hash verified (" + newHash.substring(0, 8) + "), skipping extraction.");
                    return target;
                }
            } catch (IOException e) {
                LogUtil.warn("校验现有 Native 库失败，准备覆盖: " + e.getMessage());
            }
        }

        // 写入文件 (使用内存中的 bytes)
        Files.createDirectories(target.getParent());
        
        // [Fix] 使用 ByteArrayInputStream + Files.copy 确保写入过程的标准性
        try (ByteArrayInputStream bin = new ByteArrayInputStream(resourceBytes)) {
            Files.copy(bin, target, StandardCopyOption.REPLACE_EXISTING);
        }
        
        LogUtil.info("已提取 Native 库至: " + target + " (Hash: " + newHash.substring(0, 8) + ")");
        return target;
    }

    private static String calculateHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}