---
name: sync-upstream
description: 同步上游仓库代码到本地 fork，包含子模块的同步。当用户提到"同步上游"、"拉取上游"、"sync upstream"、"更新上游代码"、"同步fork"时触发此 skill。
---

# 同步上游仓库（含子模块）

本项目是 fork 自上游仓库，且包含子模块（同样是 fork）。同步时需要遵循**先里后外**的原则。

## 项目 Fork 关系

### 主项目
```
MetaCubeX/ClashMetaForAndroid (upstream)
    └── zzw6776/ClashMetaForAndroid (origin)
```

### 子模块 (core/src/foss/golang/clash)
```
MetaCubeX/mihomo (upstream)  分支: Alpha
    └── zzw6776/mihomo (origin)  分支: Alpha
```

## 核心原则

1. **先里后外**：必须先同步子模块，再同步主项目。因为主项目记录了子模块的 commit hash，如果反过来，主项目 merge 后子模块 hash 会指向一个你的 fork 上还不存在的 commit。
2. **子模块指针记录自己 fork 的 commit**：主项目最终应记录你自己子模块 merge 后的新 commit，而不是上游主项目记录的那个 commit。上游的 commit 不包含你的改动。
3. **不要使用 `git submodule update`**：在有自己修改的 fork 场景下，`git submodule update --init --recursive` 会把子模块切到上游记录的 commit（detached HEAD），忽略你自己的改动。这个命令只适用于没有自己修改的纯同步场景。
4. **冲突处理需征求用户意见**：遇到合并冲突时，列出冲突文件和双方内容，给出建议，等待用户确认后再解决。

## 完整操作流程

### 前置检查

确认远程仓库配置，如果没有 upstream 则添加：

```bash
# 主项目
git remote -v
git remote add upstream https://github.com/MetaCubeX/ClashMetaForAndroid.git  # 如果没有

# 子模块
git -C core/src/foss/golang/clash remote -v
git -C core/src/foss/golang/clash remote add upstream https://github.com/MetaCubeX/mihomo.git  # 如果没有
```

### 第一步：同步子模块（zzw6776/mihomo <- MetaCubeX/mihomo）

```bash
# 1. 拉取上游最新代码
git -C core/src/foss/golang/clash fetch upstream

# 2. 查看差异（了解上游更新了什么）
git -C core/src/foss/golang/clash log --oneline Alpha..upstream/Alpha

# 3. 确保在 Alpha 分支上
git -C core/src/foss/golang/clash checkout Alpha

# 4. 合并上游 Alpha 分支
git -C core/src/foss/golang/clash merge upstream/Alpha
# -> 如有冲突，征求用户意见后解决
# -> 产生一个新的 merge commit（包含你的改动 + 上游新代码）

# 5. 推送到你的 fork
git -C core/src/foss/golang/clash push origin Alpha
```

### 第二步：同步主项目（zzw6776/ClashMetaForAndroid <- MetaCubeX/ClashMetaForAndroid）

```bash
# 1. 拉取上游最新代码
git fetch upstream

# 2. 查看差异
git log --oneline main..upstream/main

# 3. 合并上游 main 分支
git merge upstream/main
# -> .gitmodules 可能冲突（URL 不同），保留你的 zzw6776/mihomo
# -> 子模块指针会冲突，这是预期行为
```

### 第三步：让主项目记录你子模块的最新 commit

```bash
# 1. 把子模块指向你的 merge commit（而不是上游记录的 commit）
git add core/src/foss/golang/clash

# 2. 确认状态
git status

# 3. 提交
git commit -m "Sync with upstream MetaCubeX/ClashMetaForAndroid"

# 4. 推送
git push origin main
```

## 冲突处理指南

### 子模块指针冲突
这是最常见的冲突，因为你的 `.gitmodules` 中子模块 URL 指向 `zzw6776/mihomo`，上游指向 `MetaCubeX/mihomo`。

解决方式：
- `.gitmodules` 中的 URL **保留你自己的** `zzw6776/mihomo`
- 子模块 commit 指针用 `git add core/src/foss/golang/clash` 指向你自己的 merge commit

### 代码冲突
如果双方修改了同一文件的同一位置：
1. 列出所有冲突文件
2. 展示冲突双方的内容
3. 给出建议
4. **等待用户确认后再解决**

## 验证检查

同步完成后可以做以下验证：

```bash
# 确认主项目状态
git log --oneline -3
git status

# 确认子模块状态
git -C core/src/foss/golang/clash log --oneline -3
git -C core/src/foss/golang/clash status

# GitHub 上应显示类似：
# 主项目: "This branch is N commits ahead of MetaCubeX/ClashMetaForAndroid:main"
# 子模块: "This branch is N commits ahead of MetaCubeX/mihomo:Alpha"
# ahead 的数量 = 你自己的 commit 数 + merge commit 数
```

## 常见问题

### Q: `git submodule update --init --recursive` 什么时候用？
A: 仅在以下场景使用：
- 刚 clone 完项目，子模块目录是空的
- 你在子模块中没有自己的修改
- 不要在同步 fork 流程中使用，它会把子模块切到上游记录的 commit，忽略你的改动

### Q: 为什么不能先同步主项目再同步子模块？
A: 因为主项目 merge 后，子模块 hash 会指向上游的新 commit。如果你的子模块 fork 还没有合入上游代码，这个 commit 在你的 fork 上不存在，会导致后续操作失败。

### Q: GitHub 显示 "N commits ahead" 正常吗？
A: 完全正常。ahead 的 commit 就是你自己的改动 + merge commit，说明同步成功且你的改动保留了。如果显示 0 ahead，反而说明你的改动丢了。
