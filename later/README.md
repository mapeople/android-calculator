# 暂缓的功能（等待后续版本迭代）

按需求「功能分批上线」，这些实现已写好但暂不进入 2.1.0 的界面：

- `HistoryStore.java.bak` —— 计算历史记录（最多 60 条，SharedPreferences 持久化）。
  恢复方式：改回 `.java` 放进 `app/src/main/java/com/mapeople/calculator/`，
  在 `activity_main.xml` 顶栏加回 `btnHistory`，`MainActivity` 里接 `showHistory()`。

后续版本计划（未实现）：
- 历史记录 + 点选回填
- 长按结果复制到剪贴板
- 陀螺仪视差（背景随手机倾斜轻微位移，玻璃层不动）
