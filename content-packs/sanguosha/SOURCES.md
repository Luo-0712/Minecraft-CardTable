# 三国杀内容包 — 素材来源

本包中的牌面贴图来自开源项目 **QSanguosha**（`Mogara/QSanguosha-v2`）的 `image/big-card/` 与 `image/system/card-back.png`。

- 上游仓库：https://github.com/Mogara/QSanguosha-v2
- 许可：GPLv3 + Mogara Commercial Forbidden Restriction (MCFR)
- **不可用于商业用途**；再分发请保留上游许可与来源说明。

本地整理说明：

- 牌面：取自 `image/big-card/*.png`（200×290），排除占位图 `unknown.png`。
- 牌背：`image/system/card-back.png` 拉伸至 200×290 以与牌面一致。
- 图标：由牌背缩略为 16×16。
- 结构按本仓库 `content-packs/standard` 的 format 2 内容包格式生成（`pack.json` / `cards.json` / `layout.json` / `textures/`）。

开发同步：将本目录复制或链接到 `run/config/cardtable/packs/sanguosha/` 后即可在游戏中加载。
