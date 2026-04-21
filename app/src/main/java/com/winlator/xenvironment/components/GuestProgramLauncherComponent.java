package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.core.WineInfo;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private String box64Version = DefaultVersion.BOX64;
    private String fexVersion = "";
    private int fexPreset = 0;
    private String fexPresetCustom = "";
    private String wineVersion = "";
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            extractBox64File();
            copyDefaultBox64RCFile();
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getBox64Version() {
        return box64Version;
    }

    public void setBox64Version(String box64Version) {
        this.box64Version = box64Version;
    }

    public String getFexVersion() {
        return fexVersion;
    }

    public void setFexVersion(String fexVersion) {
        this.fexVersion = fexVersion;
    }

    public int getFexPreset() {
        return fexPreset;
    }

    public void setFexPreset(int fexPreset) {
        this.fexPreset = fexPreset;
    }

    public String getFexPresetCustom() {
        return fexPresetCustom;
    }

    public void setFexPresetCustom(String fexPresetCustom) {
        this.fexPresetCustom = fexPresetCustom;
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    private int execGuestProgram() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        
        // 检测 Wine 架构
        WineInfo wineInfo = WineInfo.fromIdentifier(environment.getContext(), wineVersion);
        boolean isArm64EC = wineInfo != null && "arm64ec".equals(wineInfo.getArch());

        if (!isArm64EC) {
            // x86_64: 使用 Box64（原始逻辑）
            addBox64EnvVars(envVars);
        } else {
            // arm64ec: 使用 FEX
            addFEXEnvVars(envVars);
        }
        
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        
        // 修复路径拼接（避免双斜杠）
        String winePath = rootFS.getWinePath();
        if (winePath.startsWith("/")) winePath = winePath.substring(1);
        envVars.put("PATH", rootDir+"/"+winePath+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        
        // 根据架构设置不同的库路径
        String ldLibraryPath;
        if (!isArm64EC) {
            // x86_64: 原始逻辑
            ldLibraryPath = rootFS.getLibDir().getPath();
            envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        } else {
            // arm64ec: 参考 glibc 项目设置特殊的库路径
            File wineDirAbs = new File(rootDir, rootFS.getWinePath().substring(1));
            File wineLibDirAbs = new File(wineDirAbs, "lib");
            File wineUnixLibDir = new File(wineLibDirAbs, "wine/aarch64-unix");
            ldLibraryPath = wineUnixLibDir.getPath() + ":" + wineLibDirAbs.getPath() + ":" + rootFS.getLibDir().getPath();
            envVars.put("WINEDLLPATH", wineLibDirAbs.getPath() + "/wine");
            envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu" + ":" + ldLibraryPath);
        }
        
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_HOST_XDG_CURRENT_DESKTOP", "1");//新版wine桌面创建快捷方式需要这个

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        // 根据架构生成启动命令
        String wp = rootFS.getWinePath();
        if (wp.startsWith("/")) wp = wp.substring(1);
        
        String command;
        if (!isArm64EC) {
            // x86_64: 使用 box64 转译（拼接完整路径）
            command = rootDir+"/usr/local/bin/box64 "+rootDir+"/"+wp+"/bin/"+guestExecutable;
        } else {
            // arm64ec: 直接执行（拼接完整路径）
            command = rootDir + "/" + wp + "/bin/" + guestExecutable;
        }

        android.util.Log.d("Winlator", "Wine architecture: " + (isArm64EC ? "arm64ec" : "x86_64"));
        android.util.Log.d("Winlator", "Executing command: " + command);

        return ProcessHelper.exec(command, envVars, rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    private void addFEXEnvVars(EnvVars envVars) {
        if (fexPreset == 0) {
            envVars.put("HODLL", "libwow64fex.dll");
        } else if (fexPreset == 1) {
            envVars.put("HODLL", "wowbox64.dll");
        } else {
            envVars.remove("HODLL");
        }
        
        if (fexPresetCustom != null && !fexPresetCustom.isEmpty()) {
            // TODO: 实现 FEX 预设管理器
            // envVars.putAll(FEXPresetManager.getEnvVars(environment.getContext(), fexPresetCustom));
        }
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}