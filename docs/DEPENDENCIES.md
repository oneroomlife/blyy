# 依赖清单

本文档列出了 BLYY 项目使用的所有主要依赖及其许可证信息。

## 核心依赖

### AndroidX

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| androidx.core:core-ktx | - | Apache 2.0 | Android 核心扩展 |
| androidx.compose.* | BOM | Apache 2.0 | 声明式 UI 框架 |
| androidx.navigation:navigation-compose | 2.8.0 | Apache 2.0 | 导航组件 |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.8.0 | Apache 2.0 | ViewModel 支持 |
| androidx.activity:activity-compose | 1.9.0 | Apache 2.0 | Activity 集成 |
| androidx.room:room-* | 2.8.4 | Apache 2.0 | 本地数据库 |

### Google

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| com.google.dagger:hilt-* | 2.59.2 | Apache 2.0 | 依赖注入 |
| androidx.hilt:hilt-navigation-compose | 1.2.0 | Apache 2.0 | Hilt 导航集成 |
| com.google.android.material:material | - | Apache 2.0 | Material Design |

### Media3

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| androidx.media3:media3-exoplayer | 1.3.1 | Apache 2.0 | 音频播放器 |
| androidx.media3:media3-session | 1.3.1 | Apache 2.0 | 媒体会话 |
| androidx.media3:media3-ui | 1.3.1 | Apache 2.0 | 媒体 UI 组件 |

### 图片加载

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| io.coil-kt:coil-compose | 2.6.0 | Apache 2.0 | 图片加载库 |

### 网络 & 解析

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| org.jsoup:jsoup | 1.18.1 | MIT | HTML 解析 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.6.3 | Apache 2.0 | JSON 序列化 |

### UI 扩展

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| com.valentinilk.shimmer:compose-shimmer | 1.3.3 | Apache 2.0 | 骨架屏动画 |

### Kotlin

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| org.jetbrains.kotlin:kotlin-stdlib | - | Apache 2.0 | Kotlin 标准库 |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava | - | Apache 2.0 | 协程支持 |

## 测试依赖

| 依赖 | 许可证 | 用途 |
|-----|-------|------|
| junit:junit | Eclipse Public License 1.0 | 单元测试 |
| androidx.test.ext:junit | Apache 2.0 | Android 测试 |
| androidx.test.espresso:espresso-core | Apache 2.0 | UI 测试 |

## 许可证兼容性

所有依赖均使用以下许可证之一：
- Apache License 2.0
- MIT License
- Eclipse Public License 1.0

这些许可证与项目的 MIT 许可证兼容。

## 许可证声明

本项目使用的第三方库均保留其原始许可证和版权声明。

### Apache 2.0 许可证声明

```
Copyright (C) [Year] The Android Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

### MIT 许可证声明 (Jsoup)

```
Copyright (c) 2009-2024 Jonathan Hedley

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---

最后更新: 2026-02-28
