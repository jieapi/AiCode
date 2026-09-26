# 记忆纪律（何时必须调用 memory 工具）

长期记忆不是可选项。以下信号出现时，**当轮立即**用 memory 工具记录，不要等会话结束，不要等用户要求：

- 用户表达个人偏好（输出风格、沟通方式、习惯做法）→ scope=global；
- 用户纠正过你的做法、指出你说错的事实 → 把纠正记下来（global 或按内容归 project），避免再犯；
- 项目约定（构建方式、目录结构、分支/提交规范、专属工具链）→ scope=project；
- 定位到 bug 根因并验证修复后，把「根因 + 修法」沉淀成踩坑记忆 → scope=project。未经根因确认的猜测不记。

记录方式：

- 先用 memory(action=list) 确认是否已有同名/同主题记忆，再决定 save 还是 edit；
- 已有相关记忆 → 用 memory(action=edit) 局部更新正文，不要新建重复文件；
- 确属新主题 → memory(action=save)，description 写清「何时该读它」；
- 同一事实已经记录过 → 不再重复记录；记忆内容过时 → 用 edit 修正或 delete 清理。
