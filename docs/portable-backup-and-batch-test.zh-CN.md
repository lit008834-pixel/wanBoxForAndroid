# 跨设备备份与首次批量测速修复

作者：@author 雾晚

## 基线与证据

- 修改起点：`fix/route-rule-settings` / `943edf097e51330f0d687bc1f4c7015d9b3465f5`，对应 v3.0.5-preview.2；重新获取的 main 为 `ff4f79bbf83515ea524ce779c5a1c25b9de2b616`。最新正式版为 v3.0.4。
- 工作区没有既有跟踪文件修改；`.fixture/`、`.preview-artifacts/`、`tools/__pycache__/` 保留。
- 手动 BackupFragment.doBackup 输出 `version=1/profiles/groups/rules/settings`；自动 BackupHelper.doBackup 输出无版本的 `proxies/groups/rules/settings`。旧 BackupRestore 只接受 `version=1/profiles`，二者确实不兼容。旧手动/自动格式由起点源码与历史提交确认，不混用 Throne Desktop。
- 截图表明 URI 的 DISPLAY_NAME 或旧回退字符串为 `OWN 7130087655019816740.log`。旧 startImport 在 openInputStream 前即按后缀拒绝。没有这个文件的真实内容，不能证明它与导出备份是同一文件，更不能宣称它一定是日志或一定被改名。没有读取/上传真实用户备份。
- 手动导出使用地区化 Date.toLocaleString 和无具体 MIME 的 CreateDocument；文件名可能有冒号/斜杠。已改为 ASCII 时间戳与 application/json。

## 格式 2：可移植 JSON

顶层：`format="wanbox.android.backup"`、`schemaVersion=2`，可选 `profiles`、`groups`、`rules`、`settings` 数组。缺少的栏目不恢复，空数组表示该栏目为空。

- profiles：显式 ID、groupId、type、排序、统计/测速字段和 `bean` JSON；仅使用程序内固定协议类，不接受输入指定的运行时类名。保留代理链/负载均衡引用。
- groups：完整分组字段，包括旧 Kryo 导出遗漏的 isSelector/frontProxy/landingProxy 和订阅对象。
- rules：全部现有规则字段，包括原始 config JSON、启用、顺序、应用、出站。
- settings：`key/valueType/valueBase64`。Base64 包的是已有明确的 KeyValue 字节布局，不含 Parcel：布尔 1 字节、float/int 4 字节、大端 long 8 字节、字符串 UTF-8、字符串集合大端长度前缀+UTF-8；保存既有类型和字节值。Room schema 与存储编码不变。
- 当前混淆规则保留模型/协议字段名。新增字段/未来版本不会静默吞掉：不兼容字段给出重新导出/兼容版本迁移提示。

所有本地/分享/自动/WebDAV 导出经过 BackupHelper.doBackup。自动备份与新 WebDAV 包也使用格式 2；WebDAV 外层仍为 ZIP，根目录唯一 `OwnBox_backup.json`。文件名 `OwnBox_backup_yyyyMMdd_HHmmss.json`。分享使用 application/json、ClipData 和只读 URI grant；导出待写内容留在私有缓存，避免选择器期间 Activity 重建丢失 ByteArray。保存成功显示 SAF 实际文件名或 provider，不显示 URI 私密路径。

## 兼容与安全

- 兼容真实旧手动 version=1/profiles 和旧自动无 version/proxies（必须有相配 groups）；两种 key 同时出现拒绝，不猜测、不丢分组。
- 旧 Parcel/Base64 与嵌套 Kryo 保留严格解析。Parcel 不是长期跨设备契约；无法解析的旧布局提示原版本重新导出，不保证任意 Android/历史应用组合都可直接解码。
- JSON/ZIP 通过内容签名、版本和数据模型确认，不只信后缀。允许缺 DISPLAY_NAME、未知 MIME/不带后缀的有效备份，大小写后缀可用；`.log` 明确拒绝，即使其中是 JSON。ZIP 后缀伪装成普通 JSON拒绝。Throne `.thrbackup` 仍走独立的桌面导入入口。
- 压缩输入最多 64 MiB、JSON 16 MiB、条目最多 128、展开总量 64 MiB、每条最多 32 MiB；只接受一个根目录 JSON文件，拒绝额外非 JSON、多个 JSON、绝对路径、`..`、反斜杠/盘符。JSON 限深度 48、节点 250000、数组 10000、字段 2 MiB；单条 Base64 2 MiB、设置值 1 MiB。重复 key、尾随文本、非法 UTF-8、损坏编码均拒绝。
- 先解析所有栏目，检查 ID/引用/重复键/设置布局，再执行既有 ATTACH+单连接 SQLite 事务；取消或中途错误回滚两库。仅恢复规则但目标设备没有相应自定义出站时，明确要求同时恢复配置，避免创建悬空引用。
- 没有数据库升级/清库迁移，也不改变 WebDAV 凭据或备份范围。WebDAV 空路径的恢复默认目录纠正为与备份相同的 OwnBox；服务重启仍走现有成功恢复流程。
- 不记录节点密码/JSON内容/完整 URL/服务器响应正文。诊断只含 MIME、provider、大小、已校验的顶层字段、错误类别。

## 首次批量测速

`ConfigurationFragment.urlTest` → `UrlTest` → `NodeTestRunner` → 新的 `TestInstance` → `Libcore.URLTestSession` → 该节点的真实 sing-box outbound。

- 全局 Semaphore(4) 约束整个实例生命周期；Dispatchers.IO.limitedParallelism(4) 约束执行线程。仅 limitedParallelism 不能限制已挂起的协程数量，因此两者同时使用。
- 批量 URL 使用用户指定的 `http://cp.cloudflare.com/generate_204`；原主状态栏/VPN/Root 已连接测速 URL、超时、分派未改。
- 每次原生网络探测预算 5000ms，包含 DNS/建连/握手/响应头。准备临时核心/插件单次预算 10000ms；重试一次前延迟 300ms，新实例在旧实例完整清理后创建。不扩大已连接服务的超时。
- 当前项目真正的节点探测是 Go HTTP client 经 sing-box outbound，未改成不经过该节点的直连 OkHttp。5 秒预算传给现有 URLTestSession，覆盖 connect/read；没有引入无用的 OkHttp 客户端或明文安全例外。
- 用户/页面取消不写失败结果、不重试；子测速插件退出可以在调用方仍有效时重试。0/负结果不得记成功，旧 ping/error 开始时清空。永久配置/插件缺失错误不盲目重试。

## 验证范围

新增单测：新 DTO 读回、全部栏目、引用、版本/重复 key、损坏/过大 JSON、ZIP、假后缀/.log、ASCII 文件名；悬挂探测并发上限、URL/5000ms传递、一次重试、无效结果、用户取消和子插件失败。

新增 Android 测试：实际 BackupHelper 生成器 → 空目标数据库恢复；旧两类 Parcel 样本；取消与晚期 SQLite 故障回滚；测试 APK 专属 provider 的缺 DISPLAY_NAME、query失败、octet-stream 与读取拒绝。测试只用虚构数据。生产 Manifest 未新增 provider。

本地执行命令/CI 真实结果随交付报告记录。没有两台实际手机、OEM SAF/云盘/WebDAV 实际服务器与真实订阅节点，不声称这些实测通过；真实首次 DNS 和节点握手改善需要同一批节点对照验证。没有开启新后台轮询。

## 人工验收

1. 在手机 A 勾选所需栏目导出 JSON，确认名称为 OwnBox_backup_*.json；传给 B 后在“从文件中导入”选择这个文件，核对各栏目数量和分组/规则/出站。
2. 不选择 OWN *.log，也不要将日志改名。若文件名丢失仍可按内容识别，但日志后缀一直拒绝。
3. 旧备份若无法解码，使用可读旧备份的原应用版本重新导出/迁移；保留原文件。格式 2 不保证旧版客户端能恢复，不建议导入后降级。
4. 取消/损坏文件不应清空原数据。恢复自定义出站规则时同时恢复配置；部分恢复范围遵循勾选。
5. 对同一分组执行首次/再次批量测速，确认最多 4 个实例，失败后只重试一次，取消不继续写红色结果。
6. 备份含凭据，传输/保存仍需可信渠道；应用不能迁移源手机外部文件、已安装插件/包和权限，原备份范围不包含这些内容。
