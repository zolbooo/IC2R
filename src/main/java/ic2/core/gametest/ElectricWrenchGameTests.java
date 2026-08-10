package ic2.core.gametest;

import com.mojang.authlib.GameProfile;
import ic2.api.item.ElectricItem;
import ic2.core.block.personal.TileEntityPersonalChest;
import ic2.core.block.tileentity.Ic2TileEntityBlock;
import ic2.core.item.ElectricItemManager;
import ic2.core.item.tool.ItemToolWrenchElectric;
import ic2.core.ref.Ic2Blocks;
import ic2.core.ref.Ic2Items;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("ic2")
@PrefixGameTestTemplate(false)
public class ElectricWrenchGameTests {
  private static final String EMPTY = "gametest/empty3x3x3";

  private static final double MAX_CHARGE = 12000.0;

  private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);

  private static ItemToolWrenchElectric wrench() {
    return (ItemToolWrenchElectric) Ic2Items.ELECTRIC_WRENCH;
  }

  private static ServerPlayer makePlayer(GameTestHelper helper) {
    ServerPlayer player = helper.makeMockServerPlayerInLevel();
    player.setGameMode(GameType.SURVIVAL);
    return player;
  }

  private static Direction getMachineFacing(GameTestHelper helper) {
    return ((Ic2TileEntityBlock) Ic2Blocks.GENERATOR)
        .getFacing(helper.getLevel(), helper.absolutePos(MACHINE_POS));
  }

  // Right-click rotation is free, matching the manual wrench.
  @GameTest(template = EMPTY)
  public static void electricWrenchRotatesMachine(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.GENERATOR);
    ServerPlayer player = makePlayer(helper);
    ItemStack stack =
        ElectricItemManager.getCharged(Ic2Items.ELECTRIC_WRENCH, Double.POSITIVE_INFINITY);
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);

    Direction newFacing = getMachineFacing(helper).getClockWise();
    InteractionResult result =
        wrench()
            .onItemUseFirst(stack, Ic2GameTestUtil.useOn(helper, player, MACHINE_POS, newFacing));

    helper.assertValueEqual(result, InteractionResult.SUCCESS, "wrench use result");
    helper.assertValueEqual(getMachineFacing(helper), newFacing, "machine facing after rotation");
    helper.assertValueEqual(
        ElectricItem.manager.getCharge(stack), MAX_CHARGE, "charge after rotating");
    helper.succeed();
  }

  @GameTest(template = EMPTY)
  public static void electricWrenchMiningDropsMachineAndConsumesEnergy(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.GENERATOR);
    ServerPlayer player = makePlayer(helper);
    ItemStack stack =
        ElectricItemManager.getCharged(Ic2Items.ELECTRIC_WRENCH, Double.POSITIVE_INFINITY);
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);

    player.gameMode.destroyBlock(helper.absolutePos(MACHINE_POS));

    helper.succeedWhen(
        () -> {
          helper.assertBlockPresent(Blocks.AIR, MACHINE_POS);
          helper.assertItemEntityPresent(Ic2Blocks.GENERATOR.asItem(), MACHINE_POS, 2.0);
          helper.assertItemEntityNotPresent(Ic2Blocks.MACHINE.asItem(), MACHINE_POS, 2.0);
          helper.assertValueEqual(
              ElectricItem.manager.getCharge(stack), MAX_CHARGE - 100.0, "charge after mining");
        });
  }

  @GameTest(template = EMPTY)
  public static void electricWrenchBreaksOwnedEmptyPersonalSafe(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.PERSONAL_CHEST);
    TileEntityPersonalChest safe = helper.getBlockEntity(MACHINE_POS);
    ServerPlayer owner = makePlayer(helper);
    safe.setOwner(owner.getGameProfile());
    ItemStack stack =
        ElectricItemManager.getCharged(Ic2Items.ELECTRIC_WRENCH, Double.POSITIVE_INFINITY);
    owner.setItemInHand(InteractionHand.MAIN_HAND, stack);

    InteractionResult result =
        wrench()
            .onBlockStartBreak(
                owner,
                helper.getLevel(),
                InteractionHand.MAIN_HAND,
                helper.absolutePos(MACHINE_POS),
                Direction.NORTH);

    helper.assertValueEqual(result, InteractionResult.SUCCESS, "wrench break result");
    helper.succeedWhen(
        () -> {
          helper.assertBlockPresent(Blocks.AIR, MACHINE_POS);
          helper.assertItemEntityPresent(Ic2Blocks.PERSONAL_CHEST.asItem(), MACHINE_POS, 2.0);
          helper.assertValueEqual(
              ElectricItem.manager.getCharge(stack), MAX_CHARGE - 100.0, "charge after mining");
        });
  }

  @GameTest(template = EMPTY)
  public static void electricWrenchCannotBreakAnotherPlayersPersonalSafe(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.PERSONAL_CHEST);
    TileEntityPersonalChest safe = helper.getBlockEntity(MACHINE_POS);
    safe.setOwner(
        new GameProfile(
            UUID.fromString("5de649d8-78d4-4d55-a34d-135d3f89a01f"), "SafeOwner"));
    ServerPlayer stranger = makePlayer(helper);
    ItemStack stack =
        ElectricItemManager.getCharged(Ic2Items.ELECTRIC_WRENCH, Double.POSITIVE_INFINITY);
    stranger.setItemInHand(InteractionHand.MAIN_HAND, stack);

    InteractionResult result =
        wrench()
            .onBlockStartBreak(
                stranger,
                helper.getLevel(),
                InteractionHand.MAIN_HAND,
                helper.absolutePos(MACHINE_POS),
                Direction.NORTH);

    helper.assertValueEqual(result, InteractionResult.PASS, "wrench break result");
    helper.assertBlockPresent(Ic2Blocks.PERSONAL_CHEST, MACHINE_POS);
    helper.assertItemEntityNotPresent(Ic2Blocks.PERSONAL_CHEST.asItem(), MACHINE_POS, 2.0);
    helper.assertValueEqual(
        ElectricItem.manager.getCharge(stack), MAX_CHARGE, "charge after denied mining");
    helper.succeed();
  }

  @GameTest(template = EMPTY)
  public static void electricWrenchWithoutEnoughChargeHasNormalDestroySpeed(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.GENERATOR);
    ItemStack stack = ElectricItemManager.getCharged(Ic2Items.ELECTRIC_WRENCH, 99.0);

    helper.assertValueEqual(
        wrench().getDestroySpeed(stack, helper.getBlockState(MACHINE_POS)),
        1.0F,
        "destroy speed without enough energy");
    helper.succeed();
  }

  @GameTest(template = EMPTY)
  public static void electricWrenchWithoutChargeStillRotates(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.GENERATOR);
    ServerPlayer player = makePlayer(helper);
    ItemStack stack = new ItemStack(Ic2Items.ELECTRIC_WRENCH);
    player.setItemInHand(InteractionHand.MAIN_HAND, stack);

    Direction facingBefore = getMachineFacing(helper);
    InteractionResult result =
        wrench()
            .onItemUseFirst(
                stack,
                Ic2GameTestUtil.useOn(helper, player, MACHINE_POS, facingBefore.getClockWise()));

    helper.assertValueEqual(result, InteractionResult.SUCCESS, "wrench use result");
    helper.assertValueEqual(
        getMachineFacing(helper), facingBefore.getClockWise(), "machine facing after rotation");
    helper.assertValueEqual(ElectricItem.manager.getCharge(stack), 0.0, "charge after rotating");
    helper.succeed();
  }
}
