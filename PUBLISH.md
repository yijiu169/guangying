# 三步发布（之后每次发版只需第 3 步）

## 1. 填配置（只做一次）
```bash
cp local.properties.example local.properties
# 编辑 local.properties：gh.owner / gh.repo
```

## 2. 生成签名并配好 Secrets（只做一次）
```bash
keytool -genkeypair -v -keystore release.keystore -alias tvbox \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 换成你的密码 -keypass 换成你的密码 \
  -dname "CN=TVBox, OU=Dev, O=TVBox, L=CN, S=CN, C=CN"
base64 -w0 release.keystore > keystore.b64
```
到 GitHub 仓库 Settings → Secrets and variables → Actions，添加：

| Name | 值 |
|---|---|
| KEYSTORE_BASE64 | keystore.b64 的内容 |
| KEYSTORE_PASSWORD | 上面填的密码 |
| KEY_ALIAS | tvbox |
| KEY_PASSWORD | 上面填的密码 |

> 签名文件不要提交（已在 .gitignore 里）。

## 3. 发布
```bash
git tag v1.0.0 && git push origin v1.0.0
```
Actions 会自动：
- 构建 `assembleJavaRelease`
- 生成 `version.json`（versionCode / size / sha256）
- 发布 Release，附带 `TVBox-latest.apk` 与 `version.json`

**装上新包的电视，下次开机 5 秒后就会自动检查更新；直连 GitHub 不通时自动切镜像下载。**
