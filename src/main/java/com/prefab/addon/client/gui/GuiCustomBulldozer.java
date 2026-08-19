package com.prefab.addon.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.prefab.addon.PrefabCustomAddon;
import com.prefab.addon.client.CustomBulldozerPreviewRenderer;
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
 * 自定义推土机 GUI.
 *
 * <p>继承 prefab 原版 {@link GuiBulldozer}, 在原版「建造」「取消」基础上加:</p>
 * <ul>
 *   <li>显示当前清除区域 (长 x 宽 x 高)</li>
 *   <li>「设置」按钮: 打开子 GUI 调尺寸</li>
 *   <li>「预览」按钮: 进入 3D 黄线框预览模式 (方向键移动, ALT 清除)</li>
 * </ul>
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
        // 用父类 GuiStructure 的 public BlockPos pos 字段, 不要在本类里再定义同名字段 (会 shadow, 父类 Initialize() 拿不到).
        this.pos = pos.immutable();
        // 注意: specificConfiguration 此时还是 null, 父类 GuiBulldozer.Initialize() 里才创建并赋给 this.pos,
        // 所以不能再写 this.specificConfiguration.pos = this.pos (会 NPE).
    }

    @Override
    protected void Initialize() {
        super.Initialize();
        // 原版的 build/cancel 按钮是放在 super.Initialize 里的. 我们重新排版, 把这两个
        // 按钮往下挪一行, 上面放 settings / preview.
        // 父类按钮坐标是按 256x256 大图算的 (grayBoxX + 10/147, grayBoxY + 136),
        // 我们这里只追加新按钮, 不动父类按钮位置, 避免破坏 prefab 的图像资源.
        int btnW = 56;
        int btnH = 18;
        int gap = 4;
        // 按钮向右放: 面板 ~350 宽, 3 按钮 (56*3 + 4*2 = 176), 留右边 12px → 起始 x = 350-176-12 = 162
        int row2Y = this.height - 28;
        int startX = this.width - 3 * btnW - 2 * gap - 12;

        // 「设置」按钮: 调尺寸 (打开 LdLib 子 GUI)
        this.btnSettings = Button.builder(Component.literal("§6⚙ 设置"), b -> {
            GuiCustomBulldozerSettings.open(this, this.stack);
        }).bounds(startX, row2Y, btnW, btnH).build();

        // 「预览」按钮: 进入 3D 黄线框
        this.btnPreview = Button.builder(Component.literal("§e📐 预览"), b -> {
            enterPreview();
        }).bounds(startX + btnW + gap, row2Y, btnW, btnH).build();

        // 「建造/清除」按钮 (跟原版 build 一样, 但发我们的网络包)
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
        Direction facing = this.minecraft.player.getDirection().getOpposite();
        CustomBulldozerPreviewRenderer.start(this.pos, L, W, H, facing, this.stack);
        this.minecraft.setScreen(null);
        this.minecraft.player.sendSystemMessage(Component.literal(
            "§e进入预览模式: 方向键移动, §l§6ALT§r§e确认清除, 右键取消"
        ));
    }

    private void executeClear() {
        int L = ItemCustomBulldozer.getLength(this.stack);
        int W = ItemCustomBulldozer.getWidth(this.stack);
        int H = ItemCustomBulldozer.getHeight(this.stack);
        // 自定义推土机固定 noDrops = true (任何模式都不生成掉落物, 性能最优)
        Direction facing = this.minecraft.player.getDirection().getOpposite();
        PacketDistributor.sendToServer(new ExecuteCustomBulldozerPayload(this.pos, L, W, H, facing, true));
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

        // 说明文字 (用原版方法绘制, 走 prefab 风格)
        String desc = "§7右键放置起点 → 设置尺寸 → 直接点 §a§l清除§7 或点 §e📐 预览§7 进入 3D 预览模式";
        int linesY = y + 64;
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
