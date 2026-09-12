# Card Table — Minecraft 规则中立桌游桌面

Card Table 是一个 Minecraft Forge 模组（模组 ID：`cardtable`），在 Minecraft 多人世界中提供一个共享的卡牌桌面。玩家放置"牌桌"方块后，可以在桌面上摆放、查看、移动、翻面、旋转、洗牌和抽取卡牌，并与同桌玩家实时共享桌面状态。

本模组受 Tabletop Simulator 类桌面沙盒思路影响，但不是其复刻：核心模组只模拟桌面上的物理对象和信息显示，**不理解任何具体桌游规则**。

**本项目按"桌游模拟器"的边界设计，不按传统网游设计**：核心提供的是一块可操作的桌面沙盒，对局如何进行完全由玩家自治。不引入传统网游的系统形态——没有系统发起的对局、没有匹配和排位、没有任务与奖励、没有角色成长，服务端不充当游戏运营商或裁判。

## 核心原则

1. **规则中立**：不判断某张牌是否可以打、不判断回合、不判断胜负、不计算分数。
2. **玩家自治**：玩家自行约定并执行桌游规则、流程和结果。
3. **可插拔内容**：卡牌、牌背、牌组和开局布局由独立内容模组提供。
4. **状态可靠**：服务端是桌面状态的唯一权威来源，负责保存和同步。
5. **隐藏信息是体验功能**：正常使用时按可见性显示牌面；不承诺对恶意客户端提供安全级别的防作弊保护。
6. **牌属于牌桌**：牌实例、牌堆、桌面区和手牌都属于服务端桌组；玩家只提供座位和可见性上下文。
7. **物理操作而非自由编辑**：支持移动、翻面、旋转、抽牌、洗牌、合堆和分堆等桌面操作，不提供任意创建、复制、删除或窥视卡牌的牌桌自由编辑模式。

## 模块规划

```text
table-api            对外稳定接口：注册与只读模型（com.example.cardtable.api 包）
        ↑
table-core           运行时：生命周期、保存、同步、渲染、权限（其余包）
        ↑
内容层               内容包（config/cardtable/packs）与第三方内容模组
```

内容模组不得假定核心会替它执行回合、判定出牌或宣布结果。

## 牌桌分组与生命周期

- 水平相邻的 `card_table` 方块按四方向连通组成一个桌组，每个方块对应一个座位，服务端以组内 master 方块保存权威组状态。
- 组级状态包括当前牌组、抽牌堆和弃牌堆；每个方块的 section 状态包括该方块桌面区、座位占用和该座位的隐藏手牌。所有这些数据都在服务端牌桌状态中，不写入玩家背包，也不随玩家离座清除。
- 玩家离座只释放座位。重新入座时，玩家看到的是该桌组当前仍保留的手牌视图；不设置保留座位或离座清牌等基于玩家的防御性策略。
- 拆桌允许进行。拆除一个桌组时，先清空该连通块的全部牌桌数据（抽牌堆、弃牌堆、桌面牌和各 section 手牌），再掉落牌桌方块和当前插入的这一副牌；不要求先把牌桌清空。

## 卡牌与内容包

卡牌是**数据**，不是物品：每张牌对应一个 `ResourceLocation` 定义（如 `cardtable:standard/ace_of_spades`），桌面上的牌实例只存 id + 朝向/旋转。进入游戏的载体是**牌组物品**（`deck`）：把一副牌插入牌桌 GUI 右上角的插槽，桌面即按其卡组集合生成整副背面朝上的抽牌堆并自动洗牌；取回牌组会把该牌组散落在各区域的牌一并收回。若需要中途把桌面收拾回初始装载形态（全部回初始堆、面朝下），使用布局声明的 `reset` 动作（默认绑定 `C`）。

### 内容包格式

运行时内容只从 `config/cardtable/packs/` 加载（zip 或目录），两侧（客户端与服务端）都需要安装。仓库内的可版本管理源包放在 `content-packs/`，开发时同步到 `run/config/cardtable/packs/`：

```
content-packs/standard/     →  run/config/cardtable/packs/standard/
content-packs/uno_cards/    →  run/config/cardtable/packs/uno_cards/
```

```
pack.json    { "format": 2, "id": "cardtable:standard", "name": "标准扑克",
               "version": "1.0.0",
               "set": { "name": "标准扑克", "back": "back" } }
cards.json   [ { "id": "ace_of_spades",
                 "display_name": {"translate": "card.cardtable.standard.ace_of_spades"},
                 "front": "ace_of_spades", "back": "back", "sort": 0 }, ... ]
layout.json  牌桌区域、初始牌堆与动作表（必填）
textures/    front/back/icon 引用的 PNG
```

- `front`/`back`/`icon` 是包内相对贴图路径（不带扩展名）；文件包贴图经客户端动态注册（`cardtable_dyn:` 命名空间）。
- 模组 jar 默认不再打包内置牌包；标准扑克与 UNO 的源文件分别在 `content-packs/standard/` 与 `content-packs/uno_cards/`。
- **`set.icon`（可选）**：牌组物品在背包、快捷栏、掉落物、手持、展示框和牌桌插槽里显示的图标，写法与 `back` 相同，例如 `"set": { "name": "UNO", "back": "back", "icon": "icon" }`（对应 `textures/icon.png`）。按 **16×16** 提供能铺满；给非正方图（如 2:3 的牌背）时自动等比缩放并居中留白，不会拉伸。**不写也不会报错**，退回链为 `set.icon` → `set.back` 的缩图 → 模组内置的通用牌组图标；声明了但文件缺失同样继续往下退，绝不阻断加载。
- 改 `set.icon` 等同于改内容：`contentHash` 随之变化，两端不一致会被上述握手点名（这一点与是否升 `version` 无关）。
- **内容一致性握手**：进服时客户端上报 `(packId, version, contentHash)`，服务端比对规范化内容的 SHA-256；缺失或不一致会被拒绝并提示具体差异。因此内容变化即使不改版本号也会被发现，构建器输出无需发版即可被检测。

### 第三方内容模组

不走文件的程序化内容只需依赖 `com.example.cardtable.api` 包，在 mod 事件总线上监听一次性的注册事件：

```java
@Mod.EventBusSubscriber(modid = "yourmod", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class YourCardContent
{
    @SubscribeEvent
    public static void onRegisterCards(RegisterCardDefinitionsEvent event)
    {
        ResourceLocation set = new ResourceLocation("yourmod", "fancy");
        event.register(CardSetDefinition.builder(set)
                .displayName(Component.literal("Fancy Deck"))
                .defaultBackTexture(new ResourceLocation("yourmod", "card/fancy/back"))
                .build());
        event.register(CardDefinition.builder(new ResourceLocation("yourmod", "fancy/sun"))
                .displayName(Component.literal("The Sun"))
                .frontTexture(new ResourceLocation("yourmod", "card/fancy/sun"))
                .cardSet(set)
                .sortIndex(0)
                .build());
    }
}
```

注册即获得：牌组物品（创造模式页自动出现）、桌面渲染、以及与所有内容一致的数据驱动同步。

## 桌面操作

右下角常显当前可执行操作；F3 可叠加显示成员数、桌组 id、各区域张数等开发调试信息。

- 右键牌桌入座并打开桌面；点击空位入座、点击自己的座位离席。
- 鼠标左键拖动：桌面牌 / 自己可见的手牌 / 牌堆顶卡拖到目标区（其他座位区、抽牌堆、弃牌堆或手牌）。
- 手牌打出时按住 `Shift` 放置：倒扣打出。
- 手牌溢出时：滚轮或中键拖拽横向翻看。
- 键盘（默认，可由内容包布局改绑）：`F` 翻面、`R` 旋转、`D` 抽牌、`S` 洗牌、`C` 收牌；`B` 展开/收起背包。
- 同桌已入座玩家的鼠标位置会以彩色箭头和玩家名标签实时显示在牌桌上；光标颜色由玩家 UUID 稳定分配，拖牌时同桌也能看见落点方向。
- 另提供合堆、分堆等整理牌堆的物理操作；不提供自由创建、复制、删除卡牌。
- 手牌数据仍由服务端桌组保存，通常只有对应座位的当前玩家可见（定向同步）；桌面牌与牌堆对所有同桌玩家同步。

## 环境要求

- Minecraft 1.20.1
- Forge 47.x
- Java 17

## 开发

```bash
./gradlew runClient    # 启动客户端
./gradlew runServer    # 启动专用服务器
./gradlew build        # 构建（含单元测试）
```

原始 Forge MDK 示例类保留在 `examples/forge-mdk/` 目录中仅供参考；该目录不参与编译，生产代码不得引用其中的类。

## 许可证

MIT，详见 [LICENSE](LICENSE)。
