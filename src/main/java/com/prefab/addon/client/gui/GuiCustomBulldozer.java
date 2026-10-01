package com.prefab.addon.client.gui;

import com.prefab.addon.client.CustomBulldozerPreviewRenderer;
import com.prefab.addon.client.PackBrowserKeyHandler;
import com.prefab.addon.items.ItemCustomBulldozer;
import com.prefab.addon.network.ExecuteCustomBulldozerPayload;
import com.prefab.structures.gui.GuiBulldozer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 自定义推土机 GUI (仅清除模式).
 *
 * <p>继承 prefab 原版 {@link GuiBulldozer}, 在原版「建造」「取消」基础上加:</p>
 * <ul>
 *   <li>显示当前清除区域 (长 x 宽 x 高)</li>
 *   <li>「设置」按钮: 打开子 GUI 调尺寸</li>
 *   <li>「预览」按钮: 进入 3D 黄线框预览模式 (方向键移动, ALT 清除)</li>
 * </ul>
 *
 * <p>注: 曾有「填充模式」, 已移除; 打开 GUI 时强制把旧 NBT 里的填充标记清掉,
 * 避免老物品残留 fillMode=true 走进填充分支.</p>
 */
public class GuiCustomBulldozer extends GuiBulldozer {

    /** 客户端右键时被 set, GUI 创建时用. 避免用 ItemStack 传参导致右键切换手时丢失. */
    public static ItemStack PENDING_STACK;
    public static BlockPos PENDING_POS;

    private final ItemStack stack;
    private Button btnSettings;
    private Button btnPreview;
    private Button btnBuildCustom;

    public GuiCustomBulldozer(ItemStack stack, BlockPos pos) {
        super();
        this.stack = stack;
        // 只保留清除模式: 清掉旧版本 NBT 里可能残留的填充标记
        ItemCustomBulldozer.setFillMode(this.stack, false);
        // 用父类 GuiStructure 的 public BlockPos pos 字段, 不要在本类里再定义同名字段 (会 shadow, 父类 Initialize() 拿不到).
        this.pos = pos.immutable();
        // 注意: specificConfiguration 此时还是 null, 父类 GuiBulldozer.Initialize() 里才创建并赋给 this.pos,
        // 所以不能再写 this.specificConfiguration.pos = this.pos (会 NPE).
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        // 3 按钮右对齐: 设置 | 预览 | 清除
        int btnW = 52;
        int btnH = 18;
        int gap = 4;
        int row2Y = this.height - 28;
        int startX = this.width - 3 * btnW - 2 * gap - 12;

        // 「设置」按钮: 调尺寸 (打开 LdLib 子 GUI)
        this.btnSettings = Button.builder(Component.literal("§6⚙ 设置"), b -> {
            GuiCustomBulldozerSettings.open(this, this.stack);
        }).bounds(startX, row2Y, btnW, btnH).build();

        // 「预览」按钮: 进入 3D 黄线框
        this.btnPreview = Button.builder(Component.literal("§e📐 预览"), b -> {
            enterPreview();
        }).bounds(startX + (btnW + gap), row2Y, btnW, btnH).build();

        // 「清除」按钮 (跟原版 build 一样, 但发我们的网络包)
        this.btnBuildCustom = Button.builder(Component.literal("§a§l清除"), b -> {
            executeClear();
        }).bounds(startX + 2 * (btnW + gap), row2Y, btnW, btnH).build();

        this.addRenderableWidget(this.btnSettings);
        this.addRenderableWidget(this.btnPreview);
        this.addRenderableWidget(this.btnBuildCustom);

        // 隐藏原版 build / cancel 按钮 (我们用自己的)
        if (this.btnBuild != null)   this.btnBuild.visible = false;
        if (this.btnCancel != null)  this.btnCancel.visible = false;
    }

    private void enterPreview() {
        int L = ItemCustomBulldozer.getLength(this.stack);
        int W = ItemCustomBulldozer.getWidth(this.stack);
        int H = ItemCustomBulldozer.getHeight(this.stack);
        Direction facing = this.minecraft.player.getDirection();  // 区域向玩家前方延伸 (原 .getOpposite() 会生成在背后)
        CustomBulldozerPreviewRenderer.start(this.pos, L, W, H, facing, this.stack);
        this.minecraft.setScreen(null);
        String moveKeys = PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_FORWARD, "↑")
            + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_BACK, "↓")
            + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_LEFT, "←")
            + PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.PREVIEW_RIGHT, "→");
        String cancelKey = PackBrowserKeyHandler.keyName(PackBrowserKeyHandler.CANCEL_PREVIEW, "右键");
        this.minecraft.player.sendSystemMessage(Component.literal(
            "§e进入预览模式: " + moveKeys + "移动, §l§6" + PackBrowserKeyHandler.buildKeyName()
                + "§r§e确认清除, " + cancelKey + "取消"
        ));
    }

    private void executeClear() {
        int L = ItemCustomBulldozer.getLength(this.stack);
        int W = ItemCustomBulldozer.getWidth(this.stack);
        int H = ItemCustomBulldozer.getHeight(this.stack);
        // noDrops 传 false: 掉落物由服务端按尺寸判定 (长宽高每个都≤16 才生成)
        Direction facing = this.minecraft.player.getDirection();  // 区域向玩家前方延伸 (原 .getOpposite() 会生成在背后)
        PacketDistributor.sendToServer(new ExecuteCustomBulldozerPayload(
            this.pos, L, W, H, facing, false, false, ""));
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 关闭 = 取消 (跟原版一致)
        if (keyCode == 256) {  // ESC
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void postButtonRender(GuiGraphics guiGraphics, int x, int y, int mouseX, int mouseY, float partialTicks) {
        // 顶部: 标题 + 区域尺寸
        guiGraphics.drawString(this.font, "§l§f自定义推土机", x + 10, y + 6, 0xFFFFFF, false);

        int L = ItemCustomBulldozer.getLength(this.stack);
        int W = ItemCustomBulldozer.getWidth(this.stack);
        int H = ItemCustomBulldozer.getHeight(this.stack);
        guiGraphics.drawString(this.font,
            String.format("§7区域: §f%dx%dx%d §7(长x宽x高)", L, W, H),
            x + 10, y + 22, 0xFFFFFF, false);
        guiGraphics.drawString(this.font,
            "§7起点: §f" + this.pos.toShortString(),
            x + 10, y + 34, 0xFFFFFF, false);
        guiGraphics.drawString(this.font,
            "§7耐久: §f" + (this.stack.getMaxDamage() - this.stack.getDamageValue()) + "/" + this.stack.getMaxDamage(),
            x + 10, y + 46, 0xFFFFFF, false);
        guiGraphics.drawString(this.font, "§7模式: §b清除模式", x + 10, y + 58, 0xFFFFFF, false);

        // 说明文字 (用原版方法绘制, 走 prefab 风格)
        String desc = "§7右键放置起点 → §6设置§7尺寸 → 直接点 §a§l清除§7 或点 §e📐 预览§7 进入 3D 预览模式";
        int linesY = y + 74;
        for (String line : desc.split("§7")) {
            if (line.isEmpty()) continue;
            guiGraphics.drawString(this.font, "§7" + line, x + 10, linesY, 0xFFFFFF, false);
            linesY += 10;
        }
    }

    // 覆盖父类 render: prefab 原版 render 是手写的 (preButton / renderButtons / postButton),
    // 没调 Screen.render 的 tooltip 流程, 所以这里也保持一致 — 父类已渲染所有 widget + 后置绘制,
    // 我们不重复调 renderTooltip 以避免 1.21.1 的签名不匹配 (旧版 Screen.renderTooltip(GuiGraphics, int, int) 已被移除).
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
    }
}
