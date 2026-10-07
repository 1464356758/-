# Camera Profile Studio 2.1

可安装的 Android 本地照片处理 App。Android 10 及以上，目标 API 35。交付 APK 保持包名和签名，可覆盖本项目此前版本。Device Profile 是设备参数模拟，不是相机采集证明。

## 产品与页面

五个主要页面：首页、照片编辑、设备选择、处理队列、验证结果。普通流程：导入照片 → 选设备/镜头/目标尺寸 → 处理并保存新副本。收藏设备置顶，设置保存在本机。原图只读，不展示或推断它以前使用的设备；方向、颜色和容器解析用于正确处理与凭证保护。

极速模式处理常规 JPEG：重建 EXIF，保留 ICC、Adobe 编码标记、量化/Huffman 表与压缩图像数据，不缩放、不重新压缩。像素重建模式支持 JPEG、PNG、WebP 和系统能够解码的 HEIC/HEIF，按目标宽高输出 JPEG。横竖自动匹配，比例不同时等比缩放并加白边；透明区填白，颜色规范为 sRGB SDR。

## 架构

| 模块 | 职责 |
| --- | --- |
| MainActivity | 五个页面、收藏、导入、用户触发的导出与分享 |
| ProcessingService / TaskStore | 串行前台服务、原子任务日志、取消、重试、中断恢复 |
| ProfileRepository / ExportSettings | JSON 档案、冻结每张任务设置、校验参数与时间 |
| PhotoEngine | 只读输入、内存/空间检查、方向、重建、验证和保存协调 |
| PixelReconstructor / ResolutionPlan | Lanczos-3 线性光重采样、精确尺寸、保守细节增强 |
| AiReconstructor / NeuralTiles | 离线 ESRGAN 分块推理、映射到任意目标尺寸 |
| JpegEngine / JpegFiles / ExifReader | TIFF/EXIF 重建、流式 JPEG 解析、独立字段回读 |
| Verification / Gallery / Exports | 解码及字段校验、相册字节回读、JPEG/ZIP 导出校验 |
| ResultProvider | 按单个 URI 授权的只读分享 |

每个任务记录 UUID、批次、输入 URI、冻结设置、状态、输出 SHA-256、验证报告和相册 URI。状态为 QUEUED、RUNNING、SUCCESS、FAILED、CANCELLED、INTERRUPTED。冷启动把未完成任务标为中断，用户可继续。已验证的私有副本优先复用，相册内容先核对，避免重复生成。系统强制停止后不会擅自重启。

## 档案数据

`assets/profiles.json` 与 UI 解耦，包含六款档案、25 个镜头/光圈配置：iPhone 18 Pro Max、HUAWEI Mate 90 Pro Max、Xiaomi 18 Pro Max、iPhone 17、LEICA Q3 43、FUJIFILM GFX100 II + GF55mmF1.7 R WR。

统一字段：id、manufacturer、model、可选 display、category、version、source、verification、lenses、output_modes、encoding_profile、可选 exposure_limits。镜头记录光圈、已核验的实际/等效焦距、可核验的镜头名；尺寸记录宽高、依据及适用镜头索引。未知物理焦距与曝光范围不编造。

部分尺寸是兼容预设，不能声称对应设备的每种模式都原生输出该尺寸；App 显示其依据。相机档案允许手动 ISO、快门、曝光补偿，经过范围校验。手机曝光范围未充分核验时，不写假定 ISO/快门。日期与 UTC 偏移分开管理，拒绝非法日历日期、超出 ±14 小时或包含秒数的偏移。GPS 始终关闭。

这是离线内置档案版，修订档案随 APK 更新，不含远程同步或自动更新。

## 重建与 AI

传统重建在**线性光**中执行 Lanczos-3，缩小时抗混叠，轻度细节增强限制单通道变化。不会恢复已经不存在的真实信息。

AI 使用 TensorFlow 官方超分示例的 ESRGAN.tflite：FLOAT32 输入 [1,50,50,3]、输出 [1,200,200,3]，0–255。权重 SHA-256：`1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b`。加载前核验权重；LiteRT 1.4.2 CPU Interpreter / XNNPACK，最多四线程。

按 34×34 有效区与上下文分块预测，映射到实际目标尺寸，边缘羽化。相对传统重建的单通道残差限制为 ±16，再乘以强度，建议 25%。能控制构图和文字变化，但预测细节未必真实，也不保证放大前后完全一致。没有整张中间 4× 画布；模型原生 4× 不等于只能输出 4×。

AI 输入上限 400 万像素，仅在放大且强度大于零时运行。普通重建输入/输出上限 5200 万像素，还受可用内存约束；不足时拒绝而不偷偷缩小。CPU 本地计算，没有 GPU/NPU 加速。

模型/运行库来源：

- https://github.com/tensorflow/examples/tree/master/lite/examples/super_resolution/android
- https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite
- https://ai.google.dev/edge/litert/android
- https://github.com/captain-pool/GSOC

LiteRT Apache-2.0 与上游实现 MIT 许可文本在 assets/licenses/。源码包不包含研究时下载的第三方摄影样片或用户测试原图。

## 原图保护与验证

普通旧 EXIF、XMP、IPTC、注释与缩略图不复制到输出；极速模式保留影响颜色和解码的 ICC / Adobe 数据。JPEG 扫描覆盖渐进多扫描及扫描间元数据。PNG、WebP、HEIF 按容器块识别常见凭证特征；识别到就停止。此保护不是完整 C2PA 或厂商签名验证器。

先生成私有完整文件，再独立 TIFF 解析、Android ExifInterface 回读、尺寸比对、解码和编码数据哈希检查。相册采用 MediaStore IS_PENDING，写后 SHA-256 回读一致才发布。失败/取消删除未发布副本，已发布且被用户修改的照片不删除。

直接 JPEG 导出复制已验证字节并回读 SHA-256。ZIP 导出逐条核对清单、SHA-256 与 CRC。不覆盖已有非空文件。建议以文件或 ZIP 传递，聊天软件的图片传输可能再次压缩或清除元数据。

输出保留软件来源与模拟说明，不伪造 MakerNote、C2PA、硬件或厂商签名。文件验证通过表示内容和配置一致，不能认证为所选设备的原片；AI、尺寸和 EXIF 无法建立真实传感器采集链。

## 构建

需要 JDK 17+、Android SDK platform 35、build-tools 35.0.0：

```bash
export ANDROID_SDK_ROOT=/absolute/path/to/android-sdk
bash build.sh
```

权重、LiteRT Java JAR 与四个 ABI 的 JNI 库已包含，无需 Gradle 或在线拉取依赖。源码包不含交付签名私钥；首次自行构建会生成新的开发签名，不能覆盖已交付 APK，除非换包名或卸载此前版本。不要把自动生成的开发密钥用于应用商店发布。

构建生成新的临时签名包，完成签名、16KB 页 ZIP 对齐与 manifest 校验后再替换最终 APK。支持 arm64-v8a、armeabi-v7a、x86、x86_64。

## 验证与范围

测试源码与交付报告在源码包 tests/ 和 TEST_REPORT.md。测试覆盖方向、线性光重采样、任意尺寸、流式渐进 JPEG、晚到元数据、凭证保护、参数回读、取消、内存门限与神经分块。Android 测试采用单独 instrumentation APK，生产包不包含测试 Provider 或测试图片。

没有 HEIC 导出、RAW/DNG、厂商 ISP 风格拟合、硬件认证、完整 HDR/JPEG-R 保留或云同步。像素重建输出 JPEG SDR；极速模式面向常规单图 SDR JPEG。后台服务仍受系统时限、强制停止和厂商省电策略约束，中断可手动继续。测试通过不能证明所有设备没有 bug。


## 2.1 导入与后台修复

导入按钮显示相册与文件夹两个入口，优先使用系统照片选择器；文件夹使用文档选择器与可持续读取授权，取消保留已有选择。

计算由用户启动的前台服务运行。Android 15+ 使用 mediaProcessing，较旧系统使用 dataSync。每两秒独立更新通知与真实耗时，每分钟续期带超时的 CPU 唤醒锁。Activity 不可见时停止页面轮询。结束后停止服务、释放锁并发送完成通知。

任务日志定期落盘，系统重投递时只恢复系统中断任务；取消、失败和系统时限后手动重试。当前照片重新计算，不是逐块断点续算。队列可进入系统通知与电池设置，App 不自动修改权限或省电设置。

本轮当前验证记录见 TEST_REPORT.md；旧结果仅作历史记录。
