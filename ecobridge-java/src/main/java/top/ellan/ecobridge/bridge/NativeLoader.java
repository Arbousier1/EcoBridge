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
 * NativeLoader (Final Version)
 * <p>
 * 修复日志:
 * 1. [新增] getLookup() 方法，支持 NativeBridge 的高效 MethodHandle 绑定。
 * 2. 保持原有的 InputStream 内存缓存逻辑，防止 ClassLoader 流关闭异常。
 * 3. 保持哈希校验，避免重复提取 IO 开销。
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
            
            // 使用 Shared Arena 允许跨线程调用
            globalArena = Arena.ofShared();
            // 加载库并获取符号查找器
            symbolLookup = SymbolLookup.libraryLookup(libPath, globalArena);
            isReady = true;

            LogUtil.debug("NativeLoader: Native 符号表已就绪。");
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

    // ✅ 必须新增：供 NativeBridge.init() 调用以绑定 MethodHandle
    public static Optional<SymbolLookup> getLookup() {
        return Optional.ofNullable(symbolLookup);
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

        byte[] resourceBytes;
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) throw new IOException("Native lib not found in jar: " + name);
            resourceBytes = in.readAllBytes();
        }

        String newHash = calculateHash(resourceBytes);

        if (Files.exists(target)) {
            try {
                byte[] existingBytes = Files.readAllBytes(target);
                String oldHash = calculateHash(existingBytes);
                if (newHash.equals(oldHash)) return target;
            } catch (IOException e) {
                LogUtil.warn("校验现有 Native 库失败，准备覆盖: " + e.getMessage());
            }
        }

        Files.createDirectories(target.getParent());
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