# 贡献指南

感谢你考虑为 BLYY 项目做出贡献！🎉

## 📋 目录

- [行为准则](#行为准则)
- [如何贡献](#如何贡献)
- [开发流程](#开发流程)
- [代码规范](#代码规范)
- [提交规范](#提交规范)
- [Pull Request 流程](#pull-request-流程)

## 行为准则

本项目采用 [Contributor Covenant](CODE_OF_CONDUCT.md) 行为准则。参与本项目即表示你同意遵守其条款。

## 如何贡献

### 报告 Bug

如果你发现了 bug，请通过 [GitHub Issues](https://github.com/oneroomlife/blyy/issues) 提交报告。提交前请：

1. 搜索现有的 Issues，确认该问题尚未被报告
2. 使用 Bug 报告模板填写详细信息
3. 提供复现步骤、预期行为和实际行为

### 提出新功能

如果你有新功能的想法：

1. 先在 [Discussions](https://github.com/oneroomlife/blyy/discussions) 中讨论
2. 确认功能符合项目定位
3. 创建 Feature Request Issue

### 提交代码

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 进行更改并提交
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

## 开发流程

### 环境设置

```bash
# 克隆你的 Fork
git clone https://github.com/your-username/blyy.git
cd blyy

# 添加上游仓库
git remote add upstream https://github.com/original-owner/blyy.git

# 同步上游更改
git fetch upstream
git checkout main
git merge upstream/main
```

### 分支命名规范

- `feature/` - 新功能 (例: `feature/add-dark-mode`)
- `fix/` - Bug 修复 (例: `fix/crash-on-rotate`)
- `refactor/` - 代码重构 (例: `refactor/viewmodel`)
- `docs/` - 文档更新 (例: `docs/readme-update`)
- `test/` - 测试相关 (例: `test/unit-tests`)

### 构建项目

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 运行测试
./gradlew test

# 代码检查
./gradlew lint
```

## 代码规范

### Kotlin 代码风格

- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 4 空格缩进
- 文件名使用 PascalCase
- 变量和函数使用 camelCase
- 常量使用 UPPER_SNAKE_CASE

### Compose 最佳实践

```kotlin
// ✅ 推荐
@Composable
fun ShipCard(
    ship: Ship,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 实现
}

// ❌ 避免
@Composable
fun shipCard(ship: Ship, onClick: () -> Unit) {
    // 实现
}
```

### 命名规范

| 类型 | 命名风格 | 示例 |
|-----|---------|------|
| 类 | PascalCase | `ShipViewModel` |
| 函数 | camelCase | `loadShips()` |
| 变量 | camelCase | `shipList` |
| 常量 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| 资源 ID | snake_case | `ic_launcher` |

## 提交规范

我们使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

### 格式

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### 类型 (type)

| 类型 | 描述 |
|-----|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档更新 |
| `style` | 代码格式（不影响功能） |
| `refactor` | 代码重构 |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `chore` | 构建/工具相关 |
| `ci` | CI 配置相关 |

### 示例

```bash
feat(player): add shuffle play feature

- Add shuffle button to player controls
- Implement random selection algorithm
- Update UI to show shuffle state

Closes #123
```

```bash
fix(repository): handle network timeout correctly

The previous implementation crashed when network
timeout occurred. Now it shows a proper error message.

Fixes #456
```

## Pull Request 流程

### PR 检查清单

- [ ] 代码遵循项目的编码规范
- [ ] 已进行自我代码审查
- [ ] 添加了必要的注释
- [ ] 更新了相关文档
- [ ] 没有引入新的警告
- [ ] 添加了必要的测试
- [ ] 所有测试通过
- [ ] PR 标题符合提交规范

### PR 标题格式

```
<type>(<scope>): <description>
```

示例：
- `feat(player): add shuffle play feature`
- `fix(ui): correct card aspect ratio`
- `docs: update installation guide`

### 审核流程

1. 提交 PR 后，维护者会进行审核
2. 根据反馈进行必要的修改
3. 至少需要一位维护者的批准
4. 通过 CI 检查后会被合并

### 合并策略

- 使用 "Squash and merge" 保持提交历史整洁
- PR 标题会作为合并提交的信息

## 需要帮助？

如果你有任何问题，可以：

- 在 [Discussions](https://github.com/oneroomlife/blyy/discussions) 中提问
- 查看 [Wiki](https://github.com/oneroomlife/blyy/wiki)
- 联系维护者

---

再次感谢你的贡献！❤️
