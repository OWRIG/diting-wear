# 执行说明：创建只有 DNS 权限的腾讯云 API 密钥

> 这份说明写给**帮用户操作腾讯云控制台的 AI 助手**（比如 Codex）。用户是新手，需要他亲自操作的步骤（登录、扫码、短信验证、确认授权），请用简单的中文告诉他该怎么做。

## 目的

谛听项目要给 `diting.missyangwx.online` 申请 HTTPS 证书。服务器上的 acme.sh 会通过 **DNSPod API** 添加一条 TXT 记录来证明域名归属（DNS-01 验证），所以需要一对**只有 DNSPod 权限**的腾讯云 API 密钥。

## 硬性规则

1. **只新建一个子用户和它的密钥**，不修改、不删除任何已有的用户、策略、资源或 DNS 记录。
2. **不要使用主账号的 API 密钥**，也不要给子用户 DNSPod 以外的任何权限。
3. **密钥不能出现在聊天、日志、终端输出、截图或 git 里。** SecretKey 只能直接写进下面指定的文件。
4. 遇到需要付费、实名认证或者不确定的操作，停下来问用户。

## 步骤

### 1. 新建子用户
腾讯云控制台 → **访问管理（CAM）** → **用户** → **用户列表** → **新建用户** → **快速创建**：
- 用户名：`diting-dns`
- 访问方式：**只勾"编程访问"**，不勾"控制台访问"
- 用户权限：搜索并**只勾选 `QcloudDNSPodFullAccess`**
  - 用户的 DNSPod 里如果有多个域名，这个权限能管理所有域名。如果用户希望只能改 `missyangwx.online`，可以改用自定义策略，把资源限定在这一个域名上。改之前先问用户。
- 创建

### 2. 保存密钥
创建成功后，页面会显示 **SecretId** 和 **SecretKey**。**SecretKey 只显示这一次。**

把它们写进下面这个文件（**整个文件覆盖写入**，不要追加）：

```
路径：/Users/rig/personal/record/.secrets/tencent.env
权限：600（chmod 600）
内容（两行，等号两边不要有空格）：
Tencent_SecretId=<SecretId>
Tencent_SecretKey=<SecretKey>
```

`.secrets/` 目录已经在 `.gitignore` 里，不会被提交。

### 3. 验证（不能打印密钥）
用这对密钥调用一个**只读**接口，确认它能用、能看到域名。示例（Python，需要装 `tencentcloud-sdk-python-dnspod`）：

```python
import os
from tencentcloud.common import credential
from tencentcloud.dnspod.v20210323 import dnspod_client, models

env = dict(l.strip().split("=", 1) for l in open(os.path.expanduser(
    "~/personal/record/.secrets/tencent.env")) if "=" in l)
cred = credential.Credential(env["Tencent_SecretId"], env["Tencent_SecretKey"])
client = dnspod_client.DnspodClient(cred, "")
req = models.DescribeRecordListRequest()
req.Domain = "missyangwx.online"
resp = client.DescribeRecordList(req)
print("OK, 记录数:", resp.RecordCountInfo.TotalCount)
print("diting 记录:", [(r.Name, r.Type, r.Value) for r in resp.RecordList if r.Name == "diting"])
```

预期输出：`OK`，并能看到 `diting  A  118.89.170.144` 这条记录。

## 完成后告诉用户

- 子用户 `diting-dns` 已创建，权限只有 `QcloudDNSPodFullAccess`
- 密钥已经存进 `.secrets/tencent.env`（权限 600）
- 验证结果：能读取到域名记录

用户会把结果转给负责整个项目的另一个 AI，由它在服务器上申请证书、配置自动续期。
