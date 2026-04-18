
# 基于 Android 的心率检测应用 (Heart Rate Monitor)
**—— Android 课程项目分析报告**

---
## 0. 前言
由于之前的下载的git项目实际是使用kotlin写的，(据说是一种比java类似但更简单的语言)，但是不符合我们课程的要求，于是采用了另外一个项目。但是这个项目也有很多问题
首先他需要登录谷歌，所以我将这个启动步骤去掉了
但是还是不可以运行，我研究了一下发现他的数据其实都上传到云端了
所以我去掉了所有的需要谷歌登录和云端的部分，并修改为本地保存
最后进行了一定的美化和功能优化（实时心率与检测时间逻辑修正）
这个项目的原地址https://github.com/shivaneej/HeartRate
先前的kotlin项目原地址https://github.com/6SUPER6SONIC6/HeartRate



---
## 1. 项目简介 (Project Overview)
本项目是一款基于 Android 平台的健康监测应用。它无需额外的心率传感器硬件，仅利用智能手机自带的**摄像头**和**闪光灯**，即可实时测量用户的心率（BPM，每分钟心跳数），并将测量结果保存在本地供历史追溯。经过现代化重构后，应用具备了 Material Design 的精美 UI 和流畅的交互体验。

---

## 2. 核心测算原理 (Core Principle)
本应用的心率测量基于**光电容积脉搏波描记法 (PPG, Photoplethysmography)** 原理。
1. **光源与反射**：在测量时，应用会强制开启手机闪光灯（Torch）。当用户将手指覆盖在摄像头和闪光灯上时，闪光灯会照亮手指内部的毛细血管。
2. **血液吸收光线**：心脏每次跳动（收缩和舒张）都会引起指尖毛细血管内血液容积的周期性变化。血液（尤其是其中的血红蛋白）对光线具有吸收作用，因此反射回摄像头的红光强度会随着心跳的节律产生极其微小的周期性明暗变化。
3. **图像处理分析**：应用通过不断捕捉摄像头的实时画面，提取每一帧图像中**红色通道 (Red Channel)** 的亮度均值。将这些均值连成一条时间曲线，曲线上的每一个“波峰 (Peak)”就代表一次心跳。

---

## 3. 代码结构与核心组件 (Architecture & Components)
项目采用纯原生的 Android 开发方式，主要由以下三个核心 Activity 构成：

*   **`MainActivity.java` (主界面)**：应用的入口。采用现代化的 `CardView` 构建仪表盘布局，提供“开始测量”与“查看历史”的导航入口。
*   **`CameraActivity.java` (测量与算法层)**：应用的核心类。负责调用底层硬件接口、实时抓取图像、运行心率算法并动态更新 UI。
*   **`view_history.java` (历史记录)**：数据展示层。负责从 Android 系统本地存储中读取历史心率数据，并以列表形式渲染到屏幕上。

---

## 4. 算法与代码逻辑解析 (Algorithm Implementation)

`CameraActivity.java` 包含了最核心的心率处理逻辑。整个流程分为四大步骤：

### 4.1 图像数据采集 (Image Capture)
应用使用 Android 较新的 **Camera2 API** 开启摄像头预览，将画面投射到 `TextureView` 上。通过监听 `TextureView.SurfaceTextureListener` 的 `onSurfaceTextureUpdated` 回调，应用可以在每一帧画面刷新时获取到一个 `Bitmap` 对象。

### 4.2 像素特征提取 (Pixel Feature Extraction)
为了兼顾性能和准确度，代码只截取了画面正中心的一小块区域（长宽各为全屏的 1/20）：
```java
// 从中心点获取 1/20 区域的像素阵列
bmp.getPixels(pixels, 0, width, width / 2, height / 2, sampleWidth, sampleHeight);

int sum = 0;
for (int i = 0; i < sampleHeight; i++) {
    for (int j = 0; j < sampleWidth; j++) {
        int pixel = pixels[i * width + j];
        int red = (pixel >> 16) & 0xFF; // 通过位移操作，专门提取红色通道(Red)的值
        sum += red;
    }
}
```
通过上述代码，应用计算出了这一帧图像红色光照强度的总和（`sum`）。

### 4.3 平滑滤波 (Rolling Average Smoothing)
由于环境光和手指微小抖动会带来大量噪点，直接比较 `sum` 会导致极差的准确度。应用采用了一套**滑动平均 (Rolling Average)** 算法对数据进行平滑处理：
```java
// 结合过去的 29 帧数据，计算包含当前帧的 30帧平滑均值
mCurrentRollingAverage = (mCurrentRollingAverage * 29 + sum) / 30;
```
这就相当于在信号处理中加了一个低通滤波器，过滤掉了高频噪点。

### 4.4 波峰检测与心率计算 (Peak Detection & BPM Calculation)
应用会记录连续三帧的平滑均值（`mLastLast`, `mLast`, `mCurrent`），通过极其经典的“局部最高点”法来判定波峰：
```java
// 如果上一帧的数据大于上上帧，且大于当前帧，说明上一帧就是一个“波峰”
if (mLastRollingAverage > mCurrentRollingAverage && mLastRollingAverage > mLastLastRollingAverage) {
    mTimeArray.add(System.currentTimeMillis()); // 记录下这次心跳发生的时间戳
    // ...
}
```
当系统收集到足够多（>3次）的心跳时间戳后，会计算相邻两次心跳的**时间间隔 (Time Distances)**。为了剔除极端的误差值，程序先将间隔数组进行排序，取**中位数 (Median)** 作为基准间隔。
最后，利用公式将毫秒间隔转化为 BPM：
```java
hrtratebpm = 60000 / med; // 一分钟是 60000 毫秒，除以心跳间隔即为每分钟心跳数
```
并通过 `runOnUiThread` 将实时计算出的数值渲染回主线程的 UI 上。

---

## 5. 数据持久化机制 (Data Persistence)
项目摒弃了笨重且需要鉴权配置的网络数据库（如 Firebase），转而使用了 Android 原生轻量级的 **`SharedPreferences`** 进行本地数据存储。
*   **存储时机**：在 `CameraActivity` 生命周期的 `onPause()` 阶段（即用户测量完毕返回主页时），触发保存逻辑。
*   **存储格式**：自动获取当前设备的日期和时间，与测得的 BPM 拼接成字符串（例如 `2026-04-18 20:30 - 75 BPM`），并将新数据附加到原有历史字符串的开头，写入 XML 文件中，实现永久留存。

---

## 6. 总结
本项目巧妙地将硬件传感器（摄像头与补光灯）与数字信号处理算法（平滑滤波与波峰检测）相结合。代码层次分明，展示了对 Android 硬件 API (Camera2)、多线程调度 (`HandlerThread` 处理图像回调，`runOnUiThread` 刷新界面) 以及本地轻量级存储技术的综合运用能力。