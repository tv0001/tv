# TVBox 源自动检测与换源

`check.py` 逐个请求 `candidates.tsv` 里的源地址，判断是否可用，然后输出：

- `out/best.json`：选中的可用配置，用来托管后让 TVBox 指向它
- `out/best-source.json`：`best.json` 来自哪个源，用于下次运行时保持不变
- `out/tvbox-multi.json`：所有可用配置的列表（`urls` 格式，按候选顺序排列，导入前请在你的客户端确认格式）
- `out/results.json`：每个点播源和直播列表的检测结果（不提交到仓库）

## 运行

```bash
cd tvbox-check
python3 -I check.py candidates.tsv out lives.tsv
```

只用标准库，无需安装依赖。`candidates.tsv`（点播配置）和 `lives.tsv`（直播列表）每行都是 `名称<Tab>地址`，`#` 开头的行是注释，格式不对的行会被跳过并提示。

## 检测规则

- **点播配置**：能拿到、能解析，有站点或直播，并且配置里的 spider jar 也能下载，才算可用。jar 拿不到时，整个源在 TVBox 里都加载不了。
- **直播列表**：从列表里均匀抽 8 个 http(s) 流地址去请求，至少 2 个有响应才算可用。
- **写入 `best.json` 的直播**：先放 `lives.tsv` 里检测通过的列表，再放配置自带且检测通过的列表，失效的会被去掉。一个都没通过时，保留配置原来的直播。
- **GitHub 地址**：`best.json` 里所有 `raw.githubusercontent.com` 地址（jar、js、直播列表）都会加上 `https://gh-proxy.com/` 前缀，方便国内访问。可以用环境变量 `GH_PROXY` 换成别的代理，设为空字符串则不加前缀。

## 央视 / 卫视直播（本地测速）

`build_lives.py` 从 `live-sources.tsv` 列出的直播列表里收集央视和卫视频道，然后**绕过本机代理直连**，逐个测试线路：对 HLS 流实际下载一个视频分片，比较下载耗时和分片的播放时长。只保留下载速度至少是播放速度 1.5 倍的线路，每个频道最多 3 条，按速度从快到慢排列，输出 `out/live.txt`。在 TVBox 里一个频道看着卡，可以左右切换到下一条线路。

```bash
cd tvbox-check
python3 -I build_lives.py live-sources.tsv out
```

这一步要在和电视盒子**同一个网络**的机器上跑。GitHub Actions 的 runner 在海外，测出来的结果不能说明家里会不会卡，所以 workflow 不跑这一步。

`out/live.txt` 和 Guovin iptv-api 在 `lives.tsv` 里标记为 `pin`，固定放在 `best.json` 直播的最前面，Actions 检测时不会把它们删掉。直播线路失效得很快，建议每隔一两天在本地重跑一次，然后提交推送 `out/live.txt`。

## 选源规则

- 上次选中的源只要还能用就继续保留，不会每次运行都换。
- 上次的源失效时，换成站点数最多的可用配置。延迟是从检测机器测出来的，不代表你那边的网络，所以不用它来选源。
- 一个可用配置都没有时，脚本以退出码 1 结束，不改动 `best.json` 和 `tvbox-multi.json`，订阅者继续使用上一次的结果。

## 定时自动换源

TVBox 本身不会自动切换配置，所以思路是：定时重新检测，把选中的配置发布到一个固定地址，TVBox 只订阅这一个地址。

仓库里的 `.github/workflows/tvbox-check.yml` 每 6 小时在 GitHub Actions 上运行一次，有变化时把结果提交回仓库。订阅地址：

```
https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
```

国内访问不稳定时可以试这些代理（2026-10-22 从海外网络测试可用，未在国内网络验证，公共代理随时可能失效）：

```
https://gh-proxy.com/https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
https://githubproxy.cc/https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
https://ghfast.top/https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
https://ghproxy.net/https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/best.json
https://cdn.jsdelivr.net/gh/tv0001/tv@main/tvbox-check/out/best.json
```

jsDelivr 的 `@main` 有缓存，更新可能延迟几个小时。

也可以在本地用 crontab 跑：

```bash
# 每 6 小时检测一次，成功时才把 best.json 复制到托管目录
0 */6 * * * cd /path/to/tvbox-check && python3 -I check.py candidates.tsv out lives.tsv && cp out/best.json /path/to/your/web/tvbox.json
```

## 当前检测结果（2026-10-22，海外网络）

点播配置：

| 名称 | 结果 |
|---|---|
| qist jsm | 可用，153 个站点（当前 `best.json`） |
| qist 潇洒 | 可用，107 个站点 |
| 摸鱼儿 | 可用，97 个站点 |
| 王小二 | 可用，63 个站点 |
| 高天流云XYQ | 可用，49 个站点 |
| 香雅情直连 | 可用，48 个站点 |
| 宝盒多仓 | 多仓索引，22 个仓库，可直接在 TVBox 里订阅，不并入输出文件 |
| 俊佬线路 | 不可用：spider jar 返回 404 |
| 其余 | 502 / 403 / 404 / 超时 / 连接失败 / 返回的是 HTML 页面 |

直播：

| 名称 | 结果 |
|---|---|
| 央视卫视(测速) | 本地直连测速，从 7 个来源合并：央视 20 个频道全部通过（含 CCTV6、CCTV4K），卫视 39 个通过 |
| Guovin iptv-api | 单个列表里表现最好，直连测速 185 条线路通过 111 条（约 60%），作为第二个直播源 |
| vbskycn | 备用，只做了抽样检测 |

本次测速没有找到合格线路的卫视：河南、海南、上海卫视等。各来源通过情况：Guovin 111/185、vbskycn 13/50、Collect-IPTV 7/16、suxuang 5/93、iptv-org 4/6、ChinaIPTV 4/19。

注意：

- `check.py` 在本机运行时走的是本机代理，GitHub Actions 的 runner 在海外。有些国内源有地区限制，在国内直连时结果可能不同。
- 测速是某一时刻的结果，晚高峰可能变慢。`vbskycn` 只做了抽样检测，里面仍有失效频道。
- 失效很快。候选列表需要定期从新的聚合页面补充。
- 检测只判断"能拿到且结构合法"，不代表内容、播放效果或安全性。

## 安全与版权

- 配置里的 `spider` / `jar` 会在你的设备上加载第三方代码，只用来源可信的配置。
- 本项目没有收录 steels/tvbox-config 仓库的内容，该仓库页面标注为"私有仓库，请勿传播"。
- 聚合页中标注"福利"类的仓库未纳入候选列表。
- 托管 `best.json` 等于转发第三方内容，请自行评估版权风险，仅供个人使用。

## 纯 CMS 点播站点（给 TV Player 用）

`cms_sources.py` 从所有可用配置里收集纯 Apple CMS（type 1）站点，再加上 `cms-candidates.tsv` 里的常见采集站，**逐个做一次真实搜索**，按域名去重，只保留真能搜出结果的，写入 `out/cms.json`（按响应速度排序）。每个站点探测两次，避免被一次超时误杀。

```bash
cd tvbox-check
python3 -I check.py candidates.tsv out lives.tsv   # 先生成 results.json
python3 -I cms_sources.py out
```

这些站点不需要 jar / js / python 运行时，所以只能跑纯接口的客户端（例如本仓库的 `tv-player/`）也能用。TV Player 内置了一份 `cms.json`，订阅更新时会从订阅目录下载新的。采集站本身时好时坏，建议和直播列表一样隔一两天重跑一次。
