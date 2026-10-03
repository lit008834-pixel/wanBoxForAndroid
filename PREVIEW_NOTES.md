# wanBox 3.0.5-preview.1

作者：@author 雾晚

## 本次更新

- 修复负载均衡业务拨号耗时污染健康探测延迟的问题；普通 TCP/UDP 连接不再改变最低延迟策略的探测排序。
- 保留 VPN / Root TUN 现有测速、默认网卡初始化、防抖及服务清理路径。
- 保持应用包名和签名，核心仍为 sing-box v1.15.0-alpha.9。

## 验证与限制

- 新增 TCP/UDP 回归测试和网络生命周期源代码契约测试。
- 未进行真机 Wi-Fi/蜂窝切换或 Root TUN 联网验证，不承诺修复所有设备连接问题。
- 详细筛选依据见 docs/upstream-network-review.zh-CN.md。
