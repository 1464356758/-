# 验证方法

从源码包根目录执行 `bash tests/run.sh`，需要 JDK 与 Python Pillow。夹具是人工构造的图像/容器，不是厂商原片或真实性凭证。

Android 检查需要 SDK platform 35、build-tools 35.0.0、platform-tools，以及 Android 15 模拟器或测试手机。先构建 app/build.sh，再执行 tests/android/build.sh，将两份 APK 安装到同一测试设备。测试 APK 与应用由同一开发密钥签名。

```bash
adb install -r app/CameraProfileStudio-2.1.apk
adb install -r tests/android/RuntimeTests.apk
adb shell am instrument -w com.cameraprofile.studio.tests/.SmokeRunner
adb shell am instrument -w com.cameraprofile.studio.tests/.FullSizeRunner
adb shell am instrument -w com.cameraprofile.studio.tests/.FinalUiRunner
```

必须按顺序运行。检查 `INSTRUMENTATION_CODE: -1` 且没有 failure 字段，不能把 adb 命令退出码 0 误当测试通过。FullSizeRunner 需要前一个检查没有留下 QUEUED 任务；FinalUiRunner 使用前一步的 24MP 已验证副本。清除测试数据时只针对这两份测试安装，注意其相册输出仍会保留。

源码包的 tests/android/assets/user.jpg 是合成的 1152×1536 夹具。交付过程中曾用用户提供的同尺寸 JPG 进行完整原生重建；该用户原图和第三方研究样片均未放入源码包。更换夹具时请保留参考尺寸与横竖比例，或相应更新 FullSizeRunner 的断言。

FixtureProvider 是仅用于 instrumentation 的独立文件提供者，模拟只读来源、写入失败和可写但不可读的已有文档。应用生产 APK 不含这些测试权限、提供者和测试照片。

GitHub 自动测试使用 `scripts/run_native.py`，分别执行 SmokeRunner、PickerRunner 和 BackgroundRunner。后台检查包含实际 100 块 ESRGAN 推理、HOME、熄屏、相册回读哈希、通知取消、唤醒锁释放与任务日志恢复。每个 runner 有独立时限，输出逐行保存；失败时仍保存 Android 日志和服务状态。

CI 使用一次性签名运行测试副本，并核对其应用 ZIP 内容与 `dist/CameraProfileStudio-2.1.apk` 完全相同。交付原签名同时独立验证。仅 APK 可安装、仅 ADB 返回 0 或仅通知计时变化都不能算原生测试通过。
