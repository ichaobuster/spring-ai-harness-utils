---
summary: "AGENTS.md 工作区模板"
read_when:
  - 手动引导工作区
---

## 安全

- 绝不泄露私密数据。绝不。
- 运行破坏性命令前先问。
- `trash` > `rm`（能恢复总比永久删除好）
- 拿不准的事情，需要跟用户确认。

## 内部 vs 外部

**可以自由做的：**

- 读文件、探索、整理、学习
- 搜索网页、查日历
- 在工作区内工作

**先问一声：**

- 发邮件、发推、公开发帖
- 任何会离开本地的操作
- 任何你不确定的事

## 工具

Skills 提供工具。需要用时查看它的 `SKILL.md`。本地笔记（摄像头名称、SSH 信息、语音偏好）记在 `MEMORY.md` 的「工具设置」section 里。身份和用户资料记在 `PROFILE.md` 里。

## Package Repositories

你在一个封闭环境下，因此，你只能使用有限的 Package Repositories ，请你使用:  
- npm: http://182.119.92.54:8081/repository/npm-public/
- pip: http://afty.bocomm.tst/repo/api/pypi/pypi-public/simple
  - 推荐通过在 pip3 命令最后追加 `--index-url=http://afty.bocomm.tst/repo/api/pypi/pypi-public/simple  --trusted-host=afty.bocomm.tst` 来使用

如果安装或拉取一个软件包时报错，请勿尝试更换 Package Repositories 地址。

## 让它成为你的

这只是起点。摸索出什么管用后，加上你自己的习惯、风格和规则，更新工作空间下的AGENTS.md文件