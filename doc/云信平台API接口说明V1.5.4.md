# 江西移动云信 API V1.5.4 整理与项目接入说明

> 2026-10-26 已完成正式查询接口只读联调。当前网关时间字段、成功码、回执字段及越界分页行为与PDF存在差异，实际开发参见 [开放接口联调记录](./云信开放接口联调记录.md)；以下保留原文协议，不自动混用版本。

来源：用户提供的《云信平台API接口说明V1.5.4(5).pdf》，共 22 页。封面版本为 V1.5.4，日期 2023-06-15；修改记录最后一项标为 V1.5.3。本文保留接口字段大小写，示例均使用虚构数据。原文示例 IP、用户名和签名不作为部署配置。

## 1. 通用协议与鉴权（原文第 5、22 页）

- 请求方式：POST，UTF-8 JSON；`Content-Type: application/json;charset=utf-8`，`Accept: application/json`。
- 网关地址由云信开户方提供，文档没有给出可直接使用的生产地址。
- 每次请求动态生成北京时间 `timestamp`，Java 格式为 `MMddHHmmss`，10 位；与服务端时间误差不超过 5 分钟。
- 请求头 `sign = MD5(userid + 接入号 + 扩展码 + timestamp)`，32 位十六进制字符串，无分隔符。本实现输出小写。
- `userid` 为 CRM 分配的 12 位企业唯一 ID，接入号为 8 位，扩展码为 6 位，均按字符串保存，保留前导零。`username` 是云信平台登录用户名，与 `userid` 不同。
- 发送请求的 `taskid` 是“接入号 + 扩展码”；响应中的 `taskid` 是云信平台返回的发送任务 ID，用于任务查询/变更，两者不可混用。

## 2. 任务发送（2.1，原文第 5–7 页）

接口定义：`/prod-api/tripartite/task/v2/send`。

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| taskid | 原文 Long，示例字符串 | 接入号与扩展码拼接，本实现使用字符串 |
| username | String | 必填，云信用户名 |
| templateId | String | 必填，审核通过的模板编号 |
| timestamp | String | 必填，动态时间戳 |
| sendnumber | String[] | 固定模板的收件人，11 位手机号 |
| varData | Object[] | 变量模板的收件人及变量；每项有 `phone` 和 `p1`、`p2` 等 |

固定模板用非空 `sendnumber` 和空 `varData`；变量模板用空 `sendnumber` 和非空 `varData`。文本变量最多 30 个，每个变量不超过 20 个字符，`p1` 对应模板中的 `#params_1#`，依此类推。

```json
{
  "taskid": "10650001000001",
  "username": "example-user",
  "templateId": "example-template",
  "timestamp": "1026143059",
  "sendnumber": [],
  "varData": [{"phone": "13800138000", "p1": "AbCd1234"}]
}
```

成功响应：`{"code":"0000000","data":"success","taskid":"1213"}`，可选 `msg`、`msgid`。只有 `code` 为字符串 `0000000` 表示任务受理成功，HTTP 200 不等于业务成功，也不代表手机已收到。

## 3. 彩信模板创建（2.2，原文第 7–11 页）

路径：`/prod-api/tripartite/template/establish`。

必填 `username`、`name`、`timestamp`、`reslist`。示例另有 `title`，参数表未说明，使用前向供应商确认。

`reslist` 元素：`index`（如 `1-1`，帧号和素材号从 1 开始，不填则每个元素独占一帧）、`type`（小写 `txt/jpg/jpeg/png/gif/mp3/wav/mp4/3gp`）、可选 `name`、`source`（`txt/file/url`）、`content`。纯文字写内容，文件写 Base64，URL 写资源地址（最多 1024 字节）。每帧最多 2 个元素且必须有文字；变量彩信文本最多 5 个变量。原文将“最大不超过 100 个字”置于 type 行，含义需供应商确认。

成功 `code=0000000`，`data` 是需要保存的模板 ID。

## 4. 任务查询（2.3，原文第 11–13 页）

路径：`/prod-api/tripartite/task/query`。

必填 `taskid`（发送响应中的任务 ID）、`username`、`timestamp`、时间范围、`pageSize`、`pageNum`；可选 `phoneno`。示例使用 `startTime`、`endTime`，格式 `yyyy-MM-dd HH:mm:ss`。参数表却写 `beginTime`，且起始时间格式写为 `yyyyMMdd h24:mm:ss`，需要联调确认。

返回 `data` 列表，包含 `id/ecid/taskid/phoneno/subject/sendstatus/sendstatustext/recvstatus/recvstatustext/sendtime/recvtime/msgid/custid` 等。原文示例 `sendstatus=0` 表示发送成功，`recvstatus=0`、`recvstatustext=DELIVRD` 表示送达。不能仅凭任务受理成功推断送达。

原文参数表成功码为 `0000000`，示例为 `00000000`；查询实现前需确认实际码值。

## 5. 文本模板创建（2.4，原文第 14–16 页）

路径：`/prod-api/tripartite/template/establishsms`。

必填：`username`、`name`、`smscontent`、`timestamp`、`mouldtype`。`smscontent` 最前面需要包含短信签名。`mouldtype="6"` 为普通文本模板；`"8"` 为变量文本模板，内容至少有一个 `#params_N#`，最多 30 个，每个变量值最多 20 字符。成功 `code=0000000`，`data` 为模板 ID，待审核通过后使用。

```json
{
  "username": "example-user",
  "name": "问卷邀请",
  "smscontent": "【您的签名】请填写调查问卷：https://survey.example.com/s/#params_1#",
  "mouldtype": "8",
  "timestamp": "1026143059"
}
```

建议将域名和 `/s/` 固定在审核模板中，仅传 8 位问卷 `formKey` 到 `p1`。不要将完整长链接直接塞入 20 字符的变量中。固定模板也可以包含某份问卷的完整链接，但每份问卷需要对应的已审核模板。

## 6. 上行短信查询（2.5，原文第 16–18 页）

路径：`/prod-api/tripartite/upstream`。

必填 `username`、`timestamp`、`startTime`、`endTime`、`pageSize`、`pageNum`；可选 `phone`，格式为 `86` 加 11 位手机号。时间格式 `yyyy-MM-dd HH:mm:ss`，跨度不能超过 24 小时。示例同时在 URL 和请求体放分页参数，需确认实际网关要求。

示例成功 `code=200`，`data` 包含 `total`、`rows`，行字段 `id/cellphone/smscode/content/recvtime`。参数表中的失败码描述沿用 `0000000`，与成功示例不一致。

## 7. 模板审核查询（2.6，原文第 18–20 页）

路径：`/prod-api/tripartite/template/status`。

必填 `templateid`（全小写）、`username`、`timestamp`。返回 `data` 的 `rx_statu/rx_desc` 为云信审核状态/描述，`cmcc_statu/cmcc_desc`、`cucc_statu/cucc_desc`、`ctcc_statu/ctcc_desc` 分别为移动、联通、电信审核状态/描述。原文描述云信审核“通过”可发省内移动，各运营商“正常可用”可发对应号码。

成功码表写 `0000000`，示例为数字 `200`，以实际网关为准。

## 8. 实时发送任务变更（2.7，原文第 20–21 页）

路径：`/prod-api/tripartite/task/change`。

必填 `taskid`（平台任务 ID）、`username`、`timestamp`、`status`（`暂停/恢复/更换`）；当 status 为 `更换` 时必填 `templateId`，必须属于本集团且审核通过。示例遗漏 taskid，调用时应遵循参数表补齐。成功码为数字 `200`，可返回 `msg`。

## 9. 本项目适配

### 配置与兼容

- 沿用 `sys_env_config` 的 `smsEnvConfig`，不新增数据库表，不新增生产 SDK。复用 Hutool HTTP/MD5/JSON、既有 `SmsService`、配置刷新事件、表单权限工具、Vue 2 和 Element UI。
- 增加渠道 `YUNXIN`，保留原有阿里云、腾讯云、中昱维信。保存配置前校验云信必填参数；保存后沿用刷新机制生效。
- 管理入口：系统管理 → 系统配置 → 短信配置 → 移动云云信。网关只填协议、主机、可选端口；发送路径默认 `/prod-api/tripartite/task/v2/send`，可选择原文示例的 `/prod-api/tripartite/task/send`。不自动切换路径重发，以免重复扣费。
- `templateId` 用于原有手机号验证码；云信将既有 `{code: "1234"}` 映射为 `p1`，验证码模板应使用 `#params_1#`。
- `yunxinInvitationTemplateId` 是问卷邀请默认模板，与验证码模板分开；发布页可指定该问卷的已审核模板，支持普通/变量模板。普通模板必须已经包含本问卷链接；变量模板默认传 `p1=formKey`，允许按审核模板调整 `p1`–`p30`。

配置示例（请替换为开户数据，不要使用示例发送）：

```json
{
  "envKey": "smsEnvConfig",
  "envValue": {
    "type": "YUNXIN",
    "yunxinBaseUrl": "https://gateway.example.com",
    "yunxinSendPath": "/prod-api/tripartite/task/v2/send",
    "yunxinUsername": "example-user",
    "yunxinUserId": "000000000001",
    "yunxinAccessNumber": "10650001",
    "yunxinExtendCode": "000001",
    "templateId": "verification-template",
    "yunxinInvitationTemplateId": "invitation-template"
  }
}
```

### 问卷邀请接口与流程

- `GET /user/form/sms/config?formKey=...`：登录并校验表单权限，仅返回云信启用状态及默认邀请模板 ID，不返回开户凭据。
- `POST /user/form/sms/invite`：登录并校验表单权限，要求问卷已发布、未删除、不是文件夹。每次最多 100 个号码（项目限制，非文档声明的网关上限），号码校验、去重后一次提交，不自动拆批或重试。

```json
{
  "formKey": "AbCd1234",
  "templateId": "invitation-template",
  "phoneNumbers": ["13800138000"],
  "templateParams": {"p1": "AbCd1234"}
}
```

固定模板提交 `templateParams: {}`。返回 `taskId`、`msgId`、去重后的 `recipientCount`。发布页展示“云信已受理”及任务 ID，表示受理状态。当前没有任务历史表、后台回执轮询或送达状态持久化。

发布页会在发送前展示收件人数并确认；提交期间禁用重复发送。请求超时/断连时状态可能未知，界面提示先到云信平台核实，避免直接重复发送。系统不记录手机号列表、正文、鉴权签名或开户凭据到发送日志。

### 适配边界与联调

本次实现任务发送（普通/变量模板）、验证码兼容、系统配置和问卷邀请入口。本文整理的模板创建、审核查询、任务查询、上行查询、任务变更供后续扩展，当前仍在云信平台操作。

部署前需提供真实网关与开户参数，申请包含正确公网问卷域名的模板并完成审核，确认发送路径、签名大小写和网关实际响应；校准服务端时间。先用可控测试号码验证变量替换、问卷访问和实际送达。没有真实账号时只能验证本地模拟网关，不能声称已经发送或送达。

### 原文差异处理汇总

| 差异 | 处理 |
| --- | --- |
| 发送定义 `/task/v2/send`，示例 `/task/send` | 默认定义地址，允许显式配置示例地址；无自动降级重发 |
| userid/接入号/扩展码长度与发送 taskid 示例不一致 | 按鉴权章节 12/8/6 位校验，taskid 由接入号和扩展码拼接 |
| 示例 sign 有的只有 16 位 | 按鉴权章节生成完整 32 位 MD5 |
| 查询/审核/上行成功码示例互不一致 | 本次仅发送，严格接受发送章节 `0000000`，不泛化所有接口成功码 |
| 查询 beginTime/startTime 与日期格式冲突 | 文档明确标注，未擅自实现 |
| 中文逗号、尾逗号、示例遗漏字段 | 使用标准 JSON，遵循必填参数定义 |

### 本地验证

```powershell
mvn -pl tduck-api -am test -DfailIfNoTests=false
```

已通过全部后端模块编译及 16 项新增测试：10 项云信模拟 HTTP 网关测试（鉴权时间戳、MD5、普通/变量报文、验证码映射、路径选择、变量边界、业务失败及无效响应），6 项邀请业务测试（配置脱敏、受理结果、去重计数、权限、发布/删除/文件夹状态、号码元素和数量校验）。测试不依赖数据库、真实账号或外网短信服务。

前端配置和使用步骤见前端仓库 `docs/yunxin.md`。已通过生产构建、相关文件 ESLint 和模拟接口页面交互检查，并完成真实云信变量模板发送联调，查询到发送状态“成功”；手机送达需以运营商送达回执为准。
