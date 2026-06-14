# 依赖清单

本文档列出了 BLYY 项目使用的所有主要依赖及其许可证信息。

> 最后更新: 2026-06-14（v1.4.0）
>
> 完整最新信息请参考 [`gradle/libs.versions.toml`](../gradle/libs.versions.toml)。

## 核心依赖

### AndroidX

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| androidx.core:core-ktx | - | Apache 2.0 | Android 核心扩展 |
| androidx.activity:activity-compose | 1.9.0 | Apache 2.0 | Activity 集成 |
| androidx.compose.* | BOM | Apache 2.0 | 声明式 UI 框架 |
| androidx.compose.material3 | - | Apache 2.0 | Material Design 3 |
| androidx.compose.material:material-icons-extended | - | Apache 2.0 | 扩展图标库 |
| androidx.compose.animation | - | Apache 2.0 | Compose 动画 |
| androidx.navigation:navigation-compose | 2.8.0 | Apache 2.0 | 导航组件 |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.8.0 | Apache 2.0 | ViewModel 支持 |
| androidx.lifecycle:lifecycle-runtime-compose | - | Apache 2.0 | Lifecycle 集成 Compose |
| androidx.room:room-* | 2.6+ | Apache 2.0 | 本地数据库 |
| androidx.datastore:datastore-preferences | 1.1+ | Apache 2.0 | 偏好设置存储 |
| androidx.media3:media3-* | 1.3+ | Apache 2.0 | 媒体播放（ExoPlayer / MediaSession） |
| androidx.hilt:hilt-navigation-compose | 1.2+ | Apache 2.0 | Hilt 导航集成 |

### Google

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| com.google.dagger:hilt-* | 2.59 | Apache 2.0 | 依赖注入 |
| com.google.android.material:material | - | Apache 2.0 | Material Design 组件 |

### 网络 & 解析

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| com.squareup.okhttp3:okhttp | 4.x | Apache 2.0 | HTTP 客户端 |
| org.jsoup:jsoup | 1.18+ | MIT | HTML 解析 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.6+ | Apache 2.0 | JSON 序列化 |
| org.jetbrains.kotlinx:kotlinx-coroutines-guava | - | Apache 2.0 | 协程支持 |

### 图片 & 媒体

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| io.coil-kt:coil-compose | 2.6+ | Apache 2.0 | 图片加载库 |
| com.valentinilk.shimmer:compose-shimmer | 1.3+ | Apache 2.0 | 骨架屏动画 |

### Kotlin

| 依赖 | 版本 | 许可证 | 用途 |
|-----|------|-------|------|
| org.jetbrains.kotlin:kotlin-stdlib | 1.9+ | Apache 2.0 | Kotlin 标准库 |
| org.jetbrains.kotlin:kotlinx-coroutines-android | 1.8+ | Apache 2.0 | Android 协程 |

## 测试依赖

| 依赖 | 许可证 | 用途 |
|-----|-------|------|
| junit:junit | Eclipse Public License 1.0 | 单元测试 |
| org.mockito:mockito-core | MIT | 模拟框架 |
| org.mockito.kotlin:mockito-kotlin | MIT | Mockito Kotlin DSL |
| app.cash.turbine:turbine | Apache 2.0 | Flow 测试 |
| org.jetbrains.kotlinx:kotlinx-coroutines-test | Apache 2.0 | 协程测试 |
| androidx.test.ext:junit | Apache 2.0 | Android 测试 |
| androidx.test.espresso:espresso-core | Apache 2.0 | UI 测试 |

## 构建工具

| 工具 | 版本 | 用途 |
|------|------|------|
| Android Gradle Plugin | 8.7+ | Android 构建 |
| Gradle | 8.7+ | 项目构建 |
| Kotlin | 1.9+ | 编译 |
| KSP | 1.9+ | 注解处理（Hilt / Room） |

## 许可证兼容性

所有依赖均使用以下许可证之一：
- Apache License 2.0
- MIT License
- Eclipse Public License 1.0

这些许可证与项目的 GNU General Public License v3.0 许可证兼容。

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

### MIT 许可证声明 (Jsoup / Mockito)

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

## 致谢

本项目使用了大量优秀的开源项目，特别感谢：

- [JetBrains Kotlin](https://kotlinlang.org/)
- [AndroidX Jetpack](https://developer.android.com/jetpack)
- [Material Design 3](https://m3.material.io/)
- [Square OkHttp](https://square.github.io/okhttp/)
- [Jonathan Hedley Jsoup](https://jsoup.org/)
- [Coil](https://coil-kt.github.io/coil/)
- [Valentin Ilk Compose Shimmer](https://github.com/valentinilk/compose-shimmer)
- [Dagger Hilt](https://dagger.dev/hilt/)
- [AndroidX Media3](https://developer.android.com/media/media3)

---

最后更新: 2026-06-14
