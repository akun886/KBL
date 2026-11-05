# THIRD-PARTY NOTICES

本项目在接口层与签名算法上参考了以下开源项目。凡直接沿用其代码或常量的文件，均在文件头注明来源。

## aaa1115910/bv — MIT License

- 项目：https://github.com/aaa1115910/bv
- 用途：
  - `net/Wbi.kt` 的 `mixinKeyEncTab`（64 位混淆表）与 `w_rid` 计算方式逐字取自
    `bili-api/.../http/util/ApiSign.kt`；
  - `net/BiliApi.kt` 的 playurl 参数组合（`fnval=4048`、`fnver=0`、`fourk=1`、`qn=127`、
    `platform=pc`）与 `player/core` 中 `OkHttpDataSource` 的 Referer/UA 注入方式；
  - `player/AudioPlayer.kt` 的纯音频播放思路（`MergedMediaSource` 仅挂音轨）。
- 另：`model/BiliModels.kt` 中 `DashData` 修正了上游两处问题（`backup_url` 缺 `@SerialName`、
  音频条目 `width/height/frame_rate/sar` 无非空默认值），属独立修改，不改变上游许可。

```
MIT License

Copyright (c) 2022 aaa1115910

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## wood3n/biu — PolyForm Noncommercial License 1.0.0

- 项目：https://github.com/wood3n/biu
- 用途：只作为「接口行为」的参考（Electron 侧拦截器为什么要注入 Referer、扫码登录的轮询状态码、
  音质枚举顺序）。**未复制其任何代码**，本项目语言与架构与之无关。
- 本项目不受其非商业条款约束，但仍在此致谢。

## SocialSisterYi/bilibili-API-collect — CC BY-NC 4.0

- 项目：https://github.com/SocialSisterYi/bilibili-API-collect
- 用途：WBI 签名、playurl、二维码登录等接口的字段含义核对。

## 商标

「KunPlayer」为本项目自有名称，不使用哔哩哔哩的 logo、粉色主色或任何暗示官方关联的视觉元素。
界面中出现的「B 站视频」等表述为描述性使用。
