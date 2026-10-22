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

直播列表（抽样 8 个流）：

| 名称 | 结果 |
|---|---|
| vbskycn | 可用，6/8 有响应，531 个流 |
| iptv-org 中国 | 可用，3/8 有响应，149 个流 |
| iptv-org 台湾 | 可用，3/8 有响应，30 个流 |
| iptv-org 香港 | 可用，2/8 有响应，17 个流 |
| 其余 | 抽样的流大多失效，或列表拿不到 |

注意：

- 检测是从海外网络发出的，GitHub Actions 的 runner 也在海外。有些国内源和直播流有地区限制，在国内网络下结果可能不同。
- 直播检测只是抽样，通过的列表里仍然会有失效的频道。
- 失效很快。候选列表需要定期从新的聚合页面补充。
- 检测只判断"能拿到且结构合法"，不代表内容、播放效果或安全性。

## 安全与版权

- 配置里的 `spider` / `jar` 会在你的设备上加载第三方代码，只用来源可信的配置。
- 本项目没有收录 steels/tvbox-config 仓库的内容，该仓库页面标注为"私有仓库，请勿传播"。
- 聚合页中标注"福利"类的仓库未纳入候选列表。
- 托管 `best.json` 等于转发第三方内容，请自行评估版权风险，仅供个人使用。
