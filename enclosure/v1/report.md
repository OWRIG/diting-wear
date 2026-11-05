谛听外壳 v1 已完成设计建模、几何验收、预览复核及本地 K3 实际切片；尚未打印或验证实物配合。

采用用户修订后的 `enclosure/DESIGN.md`：舌片 y 从21.9开始、导声管止于z=−2.4、7.1节分区包络、单边燕尾。设计 SHA-256：`6ae6d8ba3a0b16309a6812b939b156d3b33aec15d8e7e09246fa93520ea74b92`。未修改设计文档，未 commit/push，未操作打印机或发送任务。

**交付入口与文件**

- 新手先读 [PRINTING.txt](PRINTING.txt)，用 Creality Print 打开 [diting-case-v1-K3.3mf](diting-case-v1-K3.3mf) 为工程。
- [diting-case-v1-K3.gcode](diting-case-v1-K3.gcode)：本次本地实际切出的G-code。
- [build.py](build.py)：Blender后台生成；[diting-case-v1.blend](diting-case-v1.blend) 可查看和编辑。
- `parts/case-front.stl`、`parts/case-back.stl`、`parts/clip.stl`：毫米、精确轴置换、最低打印Z=0。
- `previews/`：9张Workbench预览，包括正反装配、正反爆炸、打印排布、正视、前壳内侧、燕尾槽、背夹侧视。
- [geometry-report.json](geometry-report.json)、[slicing-report.json](slicing-report.json)、[visual-review.json](visual-review.json)、[rebuild-check.json](rebuild-check.json)。
- `slice_local.py`、`verify_slicing.py`：本地CLI切片及标准库审计；`slice-invocation.json` 保存完整命令、退出码、输入输出哈希。
- `sliced/slicer.log`、`sliced/plate_1.gcode`（与交付G-code逐字节一致）、`sliced/toolpaths.png`（实际挤出路径图）。
- 旧 `preflight.py` 和阻塞版 `preflight-report.json` 已移除，预检并入build.py；本报告替换旧阻塞报告。

工程改动仅在 `enclosure/v1/`。另按用户末尾要求，将本报告复制到 `/Users/rig/.cache/cx/1106-013833-diting-case-v1/report.md`。

**几何数值（mm、mm³）**

| 零件 | 装配包围盒尺寸XYZ | STL打印尺寸XYZ | 有向体积 | 非流形边 | 连通体 |
|---|---|---|---:|---:|---:|
| `case-front` | 22.60 × 32.30 × 15.20 | 22.60 × 32.30 × 15.20 | 2167.059466 | 0 | 1 |
| `case-back` | 20.70 × 23.70 × 10.04 | 20.70 × 23.70 × 10.04 | 904.772248 | 0 | 1 |
| `clip` | 12.00 × 22.00 × 5.50 | 5.50 × 22.00 × 12.00 | 855.685965 | 0 | 1 |

- 装配包围盒：前壳 x[−11.3,11.3]、y[−8.5,23.8]、z[−15.2,0]；后盖 x[−10.35,10.35]、y[0.1,23.8]、z[−15.2,−5.16]；背夹 x[−6,6]、y[1,23]、z[−19.8,−14.3]。尺寸及每个边界都与设计推导逐项比较，误差小于0.001mm，满足0.01mm容差。
- 前壳32.3mm全长包含挂绳耳；设计的25mm主身长度不含耳。后盖20.7mm全宽包含左右卡扣凸棱。背夹5.5mm厚度包含燕尾舌和外翘1mm的导入边。
- 三件两两相交体积：前壳/后盖 **0**、前壳/背夹 **0**、后盖/背夹 **6.35e-14** mm³，均低于0.01。
- 第7.1节主体包络与前壳相交体积 **0**，最近表面间隙 **0.179704mm**（圆角附近）；USB块与前壳相交体积 **0**，最近间隙 **0.200000mm**。两块包络与后盖/背夹也无正体积干涉（最大残余仅约2.3e−13mm³，共面浮点误差）。
- 导声管端与主体包络顶面间隙 **0.200mm**；与推算麦克风顶面间隙 **1.500mm**。USB块与舌片顶面z间隙 **0.300mm**。
- USB舌片与U槽：在最终网格y=23、z=−10截面，用双向射线测得左右各 **0.100000/0.100000mm**。舌片y=21.9距PCB主体末端21.75为0.150mm。整个后盖与前壳的表面最近值约0.099257mm，发生在盖板圆角；这与舌片局部间隙是不同测量。
- 单边燕尾最终网格射线测得：竖直侧约 **0.150000mm**；斜侧近根部约 **0.149991mm**、近顶端约 **0.070008mm** 水平间隙；深度 **0.100000mm**、挡块y方向 **0.099999mm**。按斜面方程换算的最小法向间隙约 **0.054661mm**。底板与盖板外表面有设计规定的接触，无正体积重叠。
- 特征射线实测：麦克风孔1.500mm、两个顶针孔各1.600mm、挂绳孔2.600mm；两灯窗剩余壁厚各0.360mm；导声管止于−2.400mm，USB口上边z=−1.400mm，舌片顶z=−5.160mm。
- 验收在最终布尔网格及导出STL重读后执行；非流形边、连通体、体积、退化面、包围盒与落地面均检查。STL重读体积与装配网格差小于0.01mm³。装配干涉使用MANIFOLD布尔交集，不是包围盒替代。

**实现取值与尺寸解释**

参数集中在build.py开头。所有打印变换均为精确轴置换和平移，没有用三角函数作90°旋转；圆弧轮廓使用分段多边形。最终坐标量化到0.001mm并重新验收；原生3MF明确millimeter且使用与STL同一套量化网格。

盖板上下外边按0.3mm倒角处理，舌片在公共面y=21.9与中间未倒角部分精确并集，形成一个封闭连通体。夹紧凸棱未给沿y宽度，采用y=17为中心、底宽1.2/高0.6的三角棱；导入边在y=20..22.5线性向外翘1mm，保持z方向1.4厚度。这些实现取值已写入geometry-report，不改主体尺寸。

单边燕尾以明确坐标为准，未把斜侧顶端擅自改成“每边0.15”。其水平间隙从0.15渐变到0.07。单边槽的实际最大跨度为6.8mm，原文的7.6mm桥接描述作为保守上限保留，未修改原文。

**原生工程与实际切片**

Blender **5.2.2 LTS**；Creality Print **Creality_Print V7.3.1.6378**。模板为用户指定的单色41根替换插销K3工程，只读载入其机器/材料工艺；重新构造三个对象、实例与模型设置，没有继承旧对象或旧刀路缓存。

实际工艺：K3，四个机器喷嘴槽的标称喷嘴均为0.4mm；本盘只有一个逻辑材料Hyper PLA、只用T0；Textured PEI Plate。G-code继承PLA的220°C喷嘴/55°C热床设置；实际喷嘴与材料由用户手动核对。

| 参数 | 原生全局 | 三个对象覆盖 | 实际G-code |
|---|---|---|---|
| 层高/首层 | 0.16 / 0.16 | 0.16 / 0.16 | 0.16 / 0.16 |
| 墙数 | 3 | 3 | 3 |
| 填充 | 100% | 100% | 100% |
| 支撑 | 关闭 | 关闭 | 关闭，无支撑挤出 |
| brim | no_brim，宽0 | 同左 | 无brim挤出 |
| skirt/擦料塔 | 关闭 | 关闭 | 无对应挤出 |

全部改动工艺键均进入different_settings_to_system；还逐项检查了对象覆盖。层高标记浮点范围 **[0.16, 0.160001]mm**，95个Z标记严格按0.16递增至15.20（容许2e−5mm浮点误差）。墙数是有效工艺上限，薄壁处由切片器按可容纳空间生成刀路。

本次实际切片退出码 **0**，G-code **1,177,750字节**。预计 **23分37秒 / 4.70g / 1.57593m / 3.79cm³ / 95层**；前壳95层、后盖63层（末层10.08mm）、背夹75层。时间是切片器估算，额外实机准备/预热/校准可能改变用时。

三个对象在同一盘，最小XY包围盒间距 **6.35mm**；模型范围x=82.70..149.50、y=100..132.30，在260×260mm打印区域内。

除设置注释外，审计还解码 **3332条I/J圆弧** 和 **70325段正挤出路径**：所有模型挤出均在对应零件范围内；特征只有内/外墙、悬垂墙、实心填充、缝隙填充、上下表面、桥接；没有Support/Brim/Skirt/Prime tower挤出。后盖燕尾槽桥接出现在Z=1.12。启动宏前的K3原配排料线位于Y=0，单独记录，不算brim或支撑。

CLI日志将正常进度写成[error]；本次以退出码0、明确导出成功、文件存在、完整刀路审计和输入/输出哈希认定成功。没有关闭有效性检查。

3MF SHA-256：`8c32a4324b7b4c50632ef5eeeee2f2aebba3bbc1544705d06f198a86b0ee8536`。
G-code SHA-256：`fd48bdf66885aa2e589a64dfab4840e2c3d96a8cd70ec08714de8051667543b6`。

**亲眼预览与刀路复核**

已通过view_image打开全部9张模型预览以及`sliced/toolpaths.png`，不是只检查文件存在。visual-review.json保存所见内容、原图哈希以及PNG像素数据哈希。几何报告内也包含该复核文字，并确认本次重建的像素与已查看图片相同。

- 正视/内侧：麦克风孔在两顶针孔中间；导声管伸入腔内；USB端附近的两灯窗为盲孔，正面保留薄皮。
- 正反装配/爆炸：USB开口在+y端，舌片填补U槽下段；挂绳耳在−y端，与正面平齐且孔贯穿；同端后口可见撬口。
- 后盖槽与背夹侧视：槽朝USB端开放、挂绳端保留挡块，+x侧直、−x侧斜；背夹U弯在−y，夹紧棱和外翘边在+y。
- 打印排布：前壳正面向下、后盖外表面向下、背夹+x侧向下，三件相隔足够且均落Z=0。
- 实际路径复核Z=0.16、0.48、1.12、3.20、12.00、15.20：首层孔洞保留、灯窗上方形成盲孔、后盖槽有横向桥接、背夹燕尾有连续起始刀路、末层没有多余辅助结构。舌片首层两小脚贴床；这不等于实机附着已验证。

**完整验证命令与环境**

以下命令工作目录均为`/Users/rig/personal/record`；**没有额外注入环境变量**，无需事先激活Python环境。后台建模不依赖用户当前Blender场景或已运行服务。

```sh
/Applications/Blender.app/Contents/MacOS/Blender -b --factory-startup --python enclosure/v1/build.py
```

退出码0，`DITING_BUILD_OK`。为验证完整重建，先将parts、previews、geometry-report、3MF和blend移走，再运行该原样命令；15个输出均重新生成。三份STL与3MF逐字节一致；9张PNG像素数据一致，只有Blender附带的Date/RenderTime文本元数据随运行改变。rebuild-check.json记录对比；临时备份已清理。

```sh
python3 enclosure/v1/slice_local.py
```

退出码0。它实际调用的完整CLI如下（没有参数覆盖或环境变量覆盖）：

```sh
'/Applications/Creality Print.app/Contents/MacOS/CrealityPrint' --cli --slice 1 --need-gcode-file --outputdir /Users/rig/personal/record/enclosure/v1/sliced /Users/rig/personal/record/enclosure/v1/diting-case-v1-K3.3mf
```

CLI退出码0，导出plate_1.gcode后逐字节复制到交付文件，命令/日志/哈希见slice-invocation.json。后续重建的3MF哈希与实际切片输入完全一致。

```sh
python3 enclosure/v1/verify_slicing.py
```

退出码0，`SLICE_QA_OK: 23m 37s, 4.7 g, 95 layers; support/brim absent`。同时生成slicing-report.json和PRINTING.txt。

实际路径图使用Python临时依赖生成，核心建模、切片和审计都不依赖它：

```sh
uv run --no-project --no-python-downloads --python python3 --cache-dir enclosure/v1/.uv-cache --with matplotlib python enclosure/v1/verify_slicing.py --plot
```

退出码0；无额外环境变量。只安装临时Python绘图库，未安装CAD工具，未下载Python解释器；本地临时uv缓存随后清理。绘图文件保留于sliced/toolpaths.png。

**打印前最值得实物验证的三处**

1. **高度与孔位**：USB/PCB/麦克风高度来自推算；导声管对10.6mm主体包络只有0.2mm z间隙。先确认板子能无挤压放入、USB线能插到底，以及麦克风/按键/灯对位。
2. **燕尾、卡扣和桥接**：斜侧顶端水平间隙仅0.07mm（法向约0.055），燕尾槽桥接下垂或象脚会吃掉间隙。背夹燕尾起始处有约0.9mm的小侧向台阶，刀路连续仍不保证实机边缘质量。先轻推试装，不能把CAD正间隙当作实物顺滑保证。
3. **PLA弹性与首层附着**：PLA背夹只作结构打样，弯折与卡扣寿命尚未知；舌片两只小脚和侧放背夹在无brim条件下需留意床面清洁、首层附着。PETG版须换材料重新切片，不能直接复用本G-code。

本次没有实物结果，也未对打印机执行任何动作。完整交付阶段为“几何已检查、预览已查看、已本地切片”，后续打印由用户手动进行。
