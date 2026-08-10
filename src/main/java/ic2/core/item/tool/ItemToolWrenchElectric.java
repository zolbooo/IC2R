package ic2.core.item.tool;

import ic2.api.item.BlockBreakableItem;
import ic2.api.item.ElectricItem;
import ic2.api.item.IBoxable;
import ic2.api.item.IEnhancedOverlayProvider;
import ic2.api.tile.IWrenchAble;
import ic2.core.IHitSoundOverride;
import ic2.core.item.PriorityUsableItem;
import ic2.core.ref.Ic2SoundEvents;
import ic2.core.util.Ic2Tooltip;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

public class ItemToolWrenchElectric extends ItemElectricTool
    implements PriorityUsableItem,
        IBoxable,
        BlockBreakableItem,
        IEnhancedOverlayProvider,
        IHitSoundOverride {
  private static final double MINE_ENERGY_UNITS = 1.0;

  public ItemToolWrenchElectric(Properties settings) {
    super(settings, 100);
    this.tier = 1;
    this.maxCharge = 12000;
    this.transferLimit = 250;
  }

  @Override
  public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
    Player player = context.getPlayer();
    if (player == null) {
      return InteractionResult.PASS;
    }

    return ItemToolWrench.trySetFacingFromHit(context, player);
  }

  @Override
  public InteractionResult onBlockStartBreak(
      Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction) {
    return InteractionResult.PASS;
  }

  @Override
  public boolean beforeBlockBreak(
      Level world,
      Player player,
      BlockPos pos,
      BlockState state,
      @Nullable BlockEntity blockEntity) {
    if (!(state.getBlock() instanceof IWrenchAble)) {
      return true;
    }

    ItemStack stack = player.getMainHandItem();
    if (!this.canTakeDamage(stack, MINE_ENERGY_UNITS)) {
      return false;
    }

    if (ItemToolWrench.tryRemoveWithWrench(world, player, pos, state)) {
      this.consumeEnergy(stack, MINE_ENERGY_UNITS, player);
    }

    return false;
  }

  @Override
  public void afterBlockBreak(
      Level world,
      Player player,
      BlockPos pos,
      BlockState state,
      @Nullable BlockEntity blockEntity) {}

  @Override
  public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
    return ItemToolWrench.isWrenchTarget(state);
  }

  @Override
  public float getDestroySpeed(ItemStack stack, BlockState state) {
    if (this.isCorrectToolForDrops(stack, state) && this.canTakeDamage(stack, MINE_ENERGY_UNITS)) {
      return ItemToolWrench.WRENCH_DESTROY_SPEED;
    }

    return 1.0F;
  }

  public boolean canTakeDamage(ItemStack stack, double amount) {
    amount *= 100.0;
    return ElectricItem.manager.getCharge(stack) >= amount;
  }

  @Override
  public boolean consumeEnergy(ItemStack stack, double amount, LivingEntity entity) {
    double operationEnergyCost = 100.0 * amount;
    return super.consumeEnergy(stack, operationEnergyCost, entity);
  }

  @Override
  public boolean canBeStoredInToolbox(ItemStack stack) {
    return true;
  }

  @Override
  public boolean providesEnhancedOverlay(
      Level world, BlockPos pos, Direction side, Player player, ItemStack stack) {
    return world.getBlockState(pos).getBlock() instanceof IWrenchAble;
  }

  @OnlyIn(Dist.CLIENT)
  @Override
  public void appendHoverText(
      ItemStack stack, Item.TooltipContext world, List<Component> tooltip, TooltipFlag flag) {
    super.appendHoverText(stack, world, tooltip, flag);
    Component attackKey = Minecraft.getInstance().options.keyAttack.getTranslatedKeyMessage();
    Component useKey = Minecraft.getInstance().options.keyUse.getTranslatedKeyMessage();
    Ic2Tooltip.add(tooltip, Component.translatable("item.ic2.wrench.tooltip.mine", attackKey));
    Ic2Tooltip.add(tooltip, Component.translatable("item.ic2.wrench.tooltip.rotate", useKey));
  }

  @OnlyIn(Dist.CLIENT)
  @Override
  public SoundEvent getHitSoundForBlock(
      LocalPlayer player, Level world, BlockPos pos, ItemStack stack) {
    return null;
  }

  @OnlyIn(Dist.CLIENT)
  @Override
  public SoundEvent getBreakSoundForBlock(
      LocalPlayer player, Level world, BlockPos pos, ItemStack stack) {
    if (player.getAbilities().instabuild) {
      return null;
    }

    return world.getBlockState(pos).getBlock() instanceof IWrenchAble
        ? Ic2SoundEvents.ITEM_WRENCH_USE
        : null;
  }
}
