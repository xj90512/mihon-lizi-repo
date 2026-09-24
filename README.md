# 栗子漫画 · mihon 插件仓库

给 [mihon](https://github.com/mihonapp/mihon) / Tachiyomi 系 App 用的第三方扩展仓库。

## 用户怎么装

1. App 里进入 **浏览 → 扩展 → 右上角 ⋮ → 插件仓库 → + 添加**
2. 粘贴下面这条 **索引地址**：

   ```
   https://raw.githubusercontent.com/xpc010307/mihon-lizi-repo/main/index.min.json
   ```

3. 回到 **浏览 → 扩展**，把语言过滤里的 **中文** 打开，就能看到「栗子漫画」，点下载图标安装。
   （App 默认只显示系统语言的扩展，中文源必须手动勾选，否则列表是空的。）
4. 安装时会提示「允许来自此来源的应用」，同意即可。

## 目录结构

```
index.min.json   扩展索引（mihon 旧版仓库格式，首字节必须是 [）
repo.json        仓库元信息（名称 / 网站 / 签名指纹）
apk/             扩展安装包
icon/            扩展图标（<包名>.png）
```

- `apkUrl` = `<索引地址去掉 /index.min.json>/apk/<apk 字段>`
- `iconUrl` = `<索引地址去掉 /index.min.json>/icon/<包名>.png`

## 签名指纹

```
ae838d2a35da2c8c764637daff1f3272f4989d5f948879a9c12e523097c7a36f
```

扩展用固定 keystore 签名（`lizi.jks`，别名 `lizi`）。换签名会导致已装用户无法覆盖升级。

## 关于栗子漫画源

- 接口 base：`http://ai.xajtl.com`（明文 HTTP）
- 搜索 / 详情 / 章节列表 **免登录**
- **正文（`chapter/v3`）需要登录令牌**：在 App 里 **浏览 → 栗子漫画 → 工具栏 ⋮ → 设置 → Authorization** 粘贴令牌（裸 JWT，不带 `Bearer ` 前缀）
- 令牌未填时打开章节会提示怎么取令牌，不会裸抛异常
