# Camera Profile Studio 2.1

可直接安装的 Android 照片设备参数模拟、隐私整理与离线像素重建 App。

**下载安装：[CameraProfileStudio-2.1.apk](https://github.com/1464356758/-/raw/refs/heads/main/dist/CameraProfileStudio-2.1.apk)**

Android 10 及以上；与本项目 2.0 保持同一签名，可以覆盖安装。原照片始终只读，生成新副本。

- 导入照片时选择「从相册选择」或「从文件夹选择」，支持多选与取消。
- 选择设备、镜头与目标尺寸，极速整理或像素重建；普通流程不需要懂 EXIF。
- 离线 ESRGAN AI 在本机 CPU 运行，按所选目标尺寸输出，保留构图，比例不同时加白边。
- 独立前台服务处理后台队列，通知显示实际阶段与已用时间；可以取消和重试。
- 输出前独立回读验证；相册副本再校验 SHA-256，完成通知可打开结果。

使用：[使用说明](使用说明.md)。详细架构：[app/README.md](app/README.md)。

## 当前进度和测试

**本轮验证已通过：Android 14、15 各 58/58 项原生检查；八组基础回归和 25 张独立回读通过。** [查看实际完成的验证任务](https://github.com/1464356758/-/actions/runs/37569990571)。原始证据保存在 [docs/validation](docs/validation)。

[PROJECT_STATUS.json](PROJECT_STATUS.json) 是正式进度记录。[TEST_REPORT.md](TEST_REPORT.md) 区分已完成验证与待完成项目。

[Android 自动构建与原生测试](https://github.com/1464356758/-/actions/workflows/android.yml) 会核验模型依赖、编译源码、跑基础回归，再安装实际交付的签名 APK，执行核心原生检查、导入双入口检查和实际 AI 熄屏处理、取消及中断恢复检查。失败日志也保留；只有全部测试实际通过才标为通过。

聊天中断不影响已经写入的源码和状态。继续开发时读取本仓库状态与最新 Actions 结果。

## 构建与交付

`dist/CameraProfileStudio-2.1.apk` 是交付 APK，SHA-256：`4f670b5c98227855f131d122a960f4f3b5ed187c86bdf7e669947cca9423b576`。

CI 先核验交付 APK 的原签名、哈希和对齐，再以一次性测试密钥重新签名测试副本，让最新原生测试包能够执行。重新签名后逐条核对 APK 内容，应用代码、模型与资源必须与交付包完全一致。测试报告分别记录交付包哈希与测试签名，不会把 CI 测试包作为用户升级包。签名私钥不会进入仓库。

自动测试分别在 Android 14 和 15 的硬件加速模拟器运行；每项结果和失败日志均保留。模拟器通过仍不能代替用户手机的厂商省电策略实测。

AI 模型与 LiteRT 使用上游固定版本及 SHA-256 校验；许可文本保留在 app/assets/licenses。测试图均为合成图，不含用户照片。

## 实际范围

支持 JPEG、PNG、WebP 与系统可解码的 HEIC 输入；输出 JPEG。极速模式仅用于 JPEG，保持压缩图像数据与 ICC。AI 输入最多 400 万像素；普通输入与输出最多 5200 万像素，还受实际内存约束。没有 HEIC 导出、RAW 或 GPU/NPU 推理。

设备字段属于参数模拟，不提供真实相机采集证明，不伪造硬件或厂商签名。后台仍受 Android 时限、强制停止和厂商省电策略约束。没有承诺所有手机零 bug，也没有完成用户具体手机的真机测试。
