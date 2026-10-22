# TVBox 源自动检测与换源

`check.py` 逐个请求 `candidates.tsv` 里的源地址，判断是否可用，然后输出：

- `out/best.json`：选中的可用配置（相对路径的 jar/js/ext 已补成绝对地址），用来托管后让 TVBox 指向它
- `out/best-source.json`：`best.json` 来自哪个源，用于下次运行时保持不变
- `out/tvbox-multi.json`：所有可用配置的列表（`urls` 格式，按候选顺序排列，导入前请在你的客户端确认格式）
- `out/results.json`：每个地址的状态、耗时和失败原因（不提交到仓库）

## 运行

```bash
cd tvbox-check
python3 -I check.py candidates.tsv out
```

只用标准库，无需安装依赖。`candidates.tsv` 每行是 `名称<Tab>地址`，`#` 开头的行是注释，格式不对的行会被跳过并提示。

## 选源规则

- 上次选中的源只要还能用就继续保留，不会因为延迟波动来回切换。
- 上次的源失效时，才换成当前延迟最低的可用配置。
- 一个可用配置都没有时，脚本以退出码 1 结束，不改动 `best.json` 和 `tvbox-multi.json`，订阅者继续使用上一次的结果。

## 定时自动换源

TVBox 本身不会自动切换配置，所以思路是：定时重新检测，把选中的配置发布到一个固定地址，TVBox 只订阅这一个地址。

仓库里的 `.github/workflows/tvbox-check.yml` 每 6 小时在 GitHub Actions 上运行一次，有变化时把结果提交回仓库。订阅地址：

```
https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
```

国内访问不稳定时可以试这些代理（未在国内网络验证，公共代理随时可能失效）：

```
https://ghfast.top/https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
https://raw.staticdn.net/tv0001/tv/main/tvbox-check/out/best.json
https://cdn.jsdelivr.net/gh/tv0001/tv@main/tvbox-check/out/best.json
```

jsDelivr 的 `@main` 有缓存，更新可能延迟几个小时。

也可以在本地用 crontab 跑：

```bash
# 每 6 小时检测一次，成功时才把 best.json 复制到托管目录
0 */6 * * * cd /path/to/tvbox-check && python3 -I check.py candidates.tsv out && cp out/best.json /path/to/your/web/tvbox.json
```

## 当前检测结果（2026-10-22，本机网络）

| 名称 | 类型 | 结果 |
|---|---|---|
| 俊佬线路 | 配置 | 可用，24 个站点、2 个直播（当前 `best.json`） |
| 王小二 | 配置 | 可用，63 个站点、2 个直播 |
| 宝盒多仓 | 多仓索引 | 可用，22 个仓库，可直接在 TVBox 里订阅，不并入输出文件 |
| 其余 36 个 | — | 502 / 403 / 404 / 超时 / 连接失败 / 返回的是 HTML 页面 |

注意：

- 检测是从本机发出的，很多站点返回 502、403，可能是服务端或网络区域限制。GitHub Actions 的 runner 在海外，结果也可能和国内网络不同。
- 失效很快。候选列表 `candidates.tsv` 需要定期从新的聚合页面补充。
- 检测只判断"能拿到且结构合法"，不代表内容、播放效果或安全性。

## 安全与版权

- 配置里的 `spider` / `jar` 会在你的设备上加载第三方代码，只用来源可信的配置。
- 本项目没有收录 steels/tvbox-config 仓库的内容，该仓库页面标注为"私有仓库，请勿传播"。
- 聚合页中标注"福利"类的仓库未纳入候选列表。
- 托管 `best.json` 等于转发第三方内容，请自行评估版权风险，仅供个人使用。
