package ic2.core.gametest;

import ic2.api.crops.CropCard;
import ic2.api.crops.Crops;
import ic2.api.crops.ICropSeed;
import ic2.api.item.ElectricItem;
import ic2.core.Ic2Potion;
import ic2.core.block.machine.tileentity.TileEntityCropHarvester;
import ic2.core.block.machine.tileentity.TileEntityCropmatron;
import ic2.core.block.tileentity.Ic2TileEntityBlock;
import ic2.core.crop.Ic2CropType;
import ic2.core.crop.Ic2Crops;
import ic2.core.crop.TileEntityCrop;
import ic2.core.crop.cropcard.CropWheat;
import ic2.core.fluid.Ic2FluidStack;
import ic2.core.item.ElectricItemManager;
import ic2.core.item.ItemCropSeed;
import ic2.core.item.ItemTerraWart;
import ic2.core.item.tool.HandHeldCropAnalyzer;
import ic2.core.item.tool.ItemWeedingTrowel;
import ic2.core.ref.Ic2Blocks;
import ic2.core.ref.Ic2Fluids;
import ic2.core.ref.Ic2Items;
import ic2.core.util.StackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

@GameTestHolder("ic2")
@PrefixGameTestTemplate(false)
public class CropGameTests {
  private static final String EMPTY = "gametest/empty3x3x3";

  // a single crop on farmland in the middle of the template
  private static final BlockPos CROP_POS = new BlockPos(1, 2, 1);

  // crossing/spreading logic and weeds are RNG-driven; the loops below call performTick()/
  // performWeedWork() until the (high-probability) event occurs. The counts are chosen so the
  // chance of a spurious failure is far below 1e-10 per test.
  private static final int RNG_ATTEMPTS = 4000;

  private static TileEntityCrop cropAt(GameTestHelper helper, BlockPos pos) {
    BlockEntity be = helper.getBlockEntity(pos);
    if (!(be instanceof TileEntityCrop crop)) {
      throw new IllegalStateException("expected a crop tile entity at " + pos + ", found " + be);
    }

    return crop;
  }

  private static TileEntityCrop placeCropStick(GameTestHelper helper, BlockPos pos) {
    helper.setBlock(
        pos.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
    // the raw default state has crossing_base=true; regular item placement always clears it
    helper.setBlock(
        pos,
        Ic2Blocks.CROP_STICK.defaultBlockState().setValue(Ic2TileEntityBlock.CROSSING_BASE, false));
    return cropAt(helper, pos);
  }

  private static TileEntityCrop plant(
      GameTestHelper helper,
      BlockPos pos,
      CropCard card,
      int age,
      int growth,
      int gain,
      int resistance) {
    TileEntityCrop planted = placeCropStick(helper, pos).transformCropBlock(card, age);
    planted.setStatGrowth(growth);
    planted.setStatGain(gain);
    planted.setStatResistance(resistance);
    return planted;
  }

  private static UseOnContext useContextOn(
      GameTestHelper helper, Player player, BlockPos relativePos) {
    BlockPos absolute = helper.absolutePos(relativePos);
    return new UseOnContext(
        player,
        InteractionHand.MAIN_HAND,
        new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
  }

  private static boolean slotContains(ic2.core.block.invslot.InvSlot slot, Item item) {
    for (int i = 0; i < slot.size(); i++) {
      ItemStack stack = slot.get(i);
      if (!stack.isEmpty() && stack.getItem() == item) {
        return true;
      }
    }

    return false;
  }

  @GameTest(template = EMPTY)
  public static void everyCropStickStateIsMineableWithAnAxe(GameTestHelper helper) {
    helper.assertTrue(
        Ic2Blocks.CROP_STICK.defaultBlockState().is(BlockTags.MINEABLE_WITH_AXE),
        "empty crop sticks should be axe-mineable");

    for (Ic2CropType type : Ic2CropType.values()) {
      if (type == Ic2CropType.none) {
        continue;
      }

      CropCard card = Crops.instance.getCropCard(type.getOwner(), type.getName());
      helper.assertTrue(card != null, "missing crop card for " + type.getName());
      helper.assertTrue(
          card.getCropBlock().defaultBlockState().is(BlockTags.MINEABLE_WITH_AXE),
          type.getName() + " crop sticks should be axe-mineable");
    }

    helper.succeed();
  }

  @GameTest(template = EMPTY)
  public static void axeBreaksEmptyAndPlantedCropSticksFaster(GameTestHelper helper) {
    ItemStack axe = new ItemStack(Items.IRON_AXE);
    placeCropStick(helper, CROP_POS);
    helper.assertValueEqual(
        axe.getDestroySpeed(helper.getBlockState(CROP_POS)),
        6.0F,
        "iron axe speed on empty crop sticks");

    plant(helper, CROP_POS, Ic2Crops.cropWheat, 1, 0, 0, 0);
    helper.assertValueEqual(
        axe.getDestroySpeed(helper.getBlockState(CROP_POS)),
        6.0F,
        "iron axe speed on planted crop sticks");
    helper.succeed();
  }

  // Add-on crops may use the generic crop-stick block and store their age in the tile entity.
  // Advancing such a crop used to try to create an invalid age property with the range 0..0.
  @GameTest(template = EMPTY)
  public static void cropStickBackedCropCanAdvanceAge(GameTestHelper helper) {
    CropCard cropStickBackedCrop =
        new CropWheat(Ic2CropType.wheat) {
          @Override
          public net.minecraft.world.level.block.Block getCropBlock() {
            return Ic2Blocks.CROP_STICK;
          }
        };
    TileEntityCrop crop =
        placeCropStick(helper, CROP_POS).transformCropBlock(cropStickBackedCrop, 0);

    crop.setCurrentAge(1);

    helper.assertValueEqual(crop.getCurrentAge(), 1, "tile-backed crop age");
    helper.assertTrue(
        crop.getBlockState().is(Ic2Blocks.CROP_STICK),
        "tile-backed crop should remain on the crop-stick block");
    helper.succeed();
  }

  // every Ic2CropType must resolve to a registered crop card that agrees with the type about
  // its block and max age, and seed bags generated for it must resolve back to the same card.
  @GameTest(template = EMPTY)
  public static void cropRegistryIsConsistentForAllCropTypes(GameTestHelper helper) {
    for (Ic2CropType type : Ic2CropType.values()) {
      if (type == Ic2CropType.none) {
        continue;
      }

      CropCard card = Crops.instance.getCropCard(type.getOwner(), type.getName());
      helper.assertTrue(
          card != null, "crop type " + type.getName() + " has no registered crop card");
      // note: Ic2CropType.getCropBlock() cannot be used here, its block references are unreliable
      // (they are captured while Ic2Blocks is still initializing), so go through the card's block
      helper.assertTrue(
          card.getCropBlock() != null, "crop card of " + type.getName() + " has no crop block");
      helper.assertValueEqual(
          BuiltInRegistries.BLOCK.getKey(card.getCropBlock()).getPath(),
          type.getName() + "_crop",
          "crop block id of " + type.getName());
      helper.assertTrue(
          Crops.instance.getCropCard(card.getCropBlock()) == card,
          "crop block does not resolve back to its card for " + type.getName());
      // class-based cards report the 0-based block age bound, GenericCropCards report their
      // 1-based stage count (one more than the block's age bound)
      int cardMaxAge = card.getMaxAge();
      helper.assertTrue(
          cardMaxAge == type.getMaxAge() || cardMaxAge == type.getMaxAge() + 1,
          "max age of "
              + type.getName()
              + ": card says "
              + cardMaxAge
              + ", type says "
              + type.getMaxAge());
      helper.assertValueEqual(
          ((Ic2TileEntityBlock) card.getCropBlock()).getCropMaxAge(),
          type.getMaxAge(),
          "block age property bound of " + type.getName());
      helper.assertTrue(
          card.getProperties() != null, "crop card of " + type.getName() + " has no properties");

      ItemStack seed = ItemCropSeed.generateItemStackFromValues(card, 3, 5, 7, 4);
      helper.assertTrue(
          Crops.instance.getCropCard(seed) == card,
          "seed bag does not resolve back to its card for " + type.getName());
      ICropSeed seedItem = (ICropSeed) seed.getItem();
      helper.assertValueEqual(
          seedItem.getGrowthFromStack(seed), 3, "seed bag growth of " + type.getName());
      helper.assertValueEqual(
          seedItem.getGainFromStack(seed), 5, "seed bag gain of " + type.getName());
      helper.assertValueEqual(
          seedItem.getResistanceFromStack(seed), 7, "seed bag resistance of " + type.getName());
      helper.assertValueEqual(
          seedItem.getScannedFromStack(seed), 4, "seed bag scan level of " + type.getName());
    }

    helper.succeed();
  }

  // the cherry sapling crop has a fifth visual stage like the other sapling crops; its block age
  // bound was stuck at 3, clamping mature crops to the stage-3 model (upstream 18602bb9)
  @GameTest(template = EMPTY)
  public static void cherrySaplingCropReachesStageFour(GameTestHelper helper) {
    helper.assertValueEqual(
        ((Ic2TileEntityBlock) Ic2Crops.cropCherrySapling.getCropBlock()).getCropMaxAge(),
        4,
        "cherry sapling crop block age bound");

    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropCherrySapling, 4, 1, 1, 1);
    helper.assertValueEqual(
        crop.getCurrentAge(), 4, "cherry sapling crop age when planted at its final stage");
    helper.succeed();
  }

  // right-clicking an empty crop stick with a registered base seed (wheat seeds) plants the
  // matching crop with the base seed's stats and consumes one item
  @GameTest(template = EMPTY)
  public static void cropStickRightClickPlantsBaseSeed(GameTestHelper helper) {
    TileEntityCrop stick = placeCropStick(helper, CROP_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS, 2));

    helper.assertTrue(
        stick.rightClick(player, InteractionHand.MAIN_HAND), "planting a base seed should succeed");

    TileEntityCrop planted = cropAt(helper, CROP_POS);
    helper.assertTrue(
        planted.getCrop() == Ic2Crops.cropWheat,
        "wheat seeds should plant the wheat crop, got " + planted.getCrop());
    helper.assertValueEqual(planted.getCurrentAge(), 0, "freshly planted crop age");
    helper.assertValueEqual(planted.getStatGrowth(), 1, "planted growth stat");
    helper.assertValueEqual(planted.getStatGain(), 1, "planted gain stat");
    helper.assertValueEqual(planted.getStatResistance(), 1, "planted resistance stat");
    helper.assertValueEqual(
        player.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "seeds left after planting");

    helper.succeed();
  }

  // a second crop stick turns an empty crop stick into a crossing base, which then refuses seeds
  @GameTest(template = EMPTY)
  public static void secondCropStickFormsCrossingBase(GameTestHelper helper) {
    TileEntityCrop stick = placeCropStick(helper, CROP_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Ic2Items.CROP_STICK, 2));

    helper.assertTrue(
        stick.rightClick(player, InteractionHand.MAIN_HAND),
        "adding a second crop stick should succeed");
    helper.assertTrue(
        cropAt(helper, CROP_POS).isCrossingBase(), "crop stick should become a crossing base");
    helper.assertTrue(
        helper.getBlockState(CROP_POS).getValue(Ic2TileEntityBlock.CROSSING_BASE),
        "crossing base block state should be set");
    helper.assertValueEqual(
        player.getItemInHand(InteractionHand.MAIN_HAND).getCount(),
        1,
        "crop sticks left after upgrading");

    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT_SEEDS));
    helper.assertFalse(
        cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND),
        "a crossing base must not accept base seeds");
    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == null, "crossing base should still be empty");

    helper.succeed();
  }

  // using a seed bag on an empty crop stick plants the crop with the stats stored in the bag
  @GameTest(template = EMPTY)
  public static void seedBagPlantsCropWithStoredStats(GameTestHelper helper) {
    placeCropStick(helper, CROP_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    ItemStack seed = ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropTerraWart, 5, 6, 7, 4);
    player.setItemInHand(InteractionHand.MAIN_HAND, seed);

    InteractionResult result = seed.getItem().useOn(useContextOn(helper, player, CROP_POS));
    helper.assertTrue(
        result == InteractionResult.SUCCESS, "planting a seed bag should succeed, got " + result);

    TileEntityCrop planted = cropAt(helper, CROP_POS);
    helper.assertTrue(
        planted.getCrop() == Ic2Crops.cropTerraWart,
        "seed bag should plant terra wart, got " + planted.getCrop());
    helper.assertValueEqual(planted.getStatGrowth(), 5, "growth stat from the seed bag");
    helper.assertValueEqual(planted.getStatGain(), 6, "gain stat from the seed bag");
    helper.assertValueEqual(planted.getStatResistance(), 7, "resistance stat from the seed bag");
    helper.assertValueEqual(planted.getScanLevel(), 4, "scan level from the seed bag");
    helper.assertTrue(
        player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(), "seed bag should be consumed");

    helper.succeed();
  }

  // a seed bag used from the off hand must be consumed from the off hand (dupe regression: the
  // consumption cleared getInventory().selected - the main-hand hotbar slot - regardless of which
  // hand held the bag, so an off-hand bag was never consumed = infinite planting, and any unrelated
  // main-hand stack was destroyed instead)
  @GameTest(template = EMPTY)
  public static void offHandSeedBagIsConsumedFromTheOffHand(GameTestHelper helper) {
    placeCropStick(helper, CROP_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    ItemStack seed = ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropTerraWart, 5, 6, 7, 4);
    player.setItemInHand(InteractionHand.OFF_HAND, seed);
    // a distinct stack in the selected main-hand slot: the old code wiped this instead of the bag
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 5));

    BlockPos absolute = helper.absolutePos(CROP_POS);
    UseOnContext context =
        new UseOnContext(
            player,
            InteractionHand.OFF_HAND,
            new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
    InteractionResult result = seed.getItem().useOn(context);
    helper.assertTrue(
        result == InteractionResult.SUCCESS,
        "planting a seed bag from the off hand should succeed, got " + result);

    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == Ic2Crops.cropTerraWart,
        "off-hand seed bag should plant terra wart");
    helper.assertTrue(
        player.getItemInHand(InteractionHand.OFF_HAND).isEmpty(),
        "the off-hand seed bag must be consumed");
    ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
    helper.assertTrue(
        mainHand.getItem() == Items.DIAMOND && mainHand.getCount() == 5,
        "the selected main-hand stack must be left untouched, has " + mainHand);

    helper.succeed();
  }

  // picking a mature crop destroys it and drops seeds; resistance 31 pushes the first seed roll
  // above 100%, so at least one seed bag is guaranteed
  @GameTest(template = EMPTY)
  public static void pickingMatureCropDropsSeedBagAndResetsStick(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropWheat, 7, 31, 31, 31);

    helper.assertTrue(crop.pick(), "picking a planted crop should succeed");

    helper.assertBlockPresent(Ic2Blocks.CROP_STICK, CROP_POS);
    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == null, "picked crop stick should be empty");
    helper.assertItemEntityPresent(Ic2Items.CROP_SEED_BACK, CROP_POS, 2.0);

    helper.succeed();
  }

  // right-click harvesting a mature slime plant drops slimeballs and resets the crop below the
  // harvest threshold, so an immediate second harvest must fail (dupe regression, upstream
  // 9d2dc383: afterHarvestSize 3 left the crop harvestable for infinite slimeball drops)
  @GameTest(template = EMPTY)
  public static void harvestedSlimePlantResetsToNonHarvestableStage(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropSlimePlant, 3, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertTrue(
        Ic2Crops.cropSlimePlant.canBeHarvested(crop),
        "a slime plant at max age should be harvestable");

    // a single harvest may roll zero drops, so re-mature and retry until one drops
    boolean harvested = false;
    for (int i = 0; i < RNG_ATTEMPTS && !harvested; i++) {
      cropAt(helper, CROP_POS).setCurrentAge(3);
      harvested = cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND);
    }

    helper.assertTrue(harvested, "right-clicking a mature slime plant should drop slimeballs");
    helper.assertItemEntityPresent(Items.SLIME_BALL, CROP_POS, 2.0);

    TileEntityCrop remainder = cropAt(helper, CROP_POS);
    helper.assertTrue(
        remainder.getCrop() == Ic2Crops.cropSlimePlant,
        "harvesting must not destroy the slime plant");
    helper.assertValueEqual(remainder.getCurrentAge(), 2, "slime plant age after harvest");
    helper.assertFalse(
        Ic2Crops.cropSlimePlant.canBeHarvested(remainder),
        "a freshly harvested slime plant must not be harvestable again");
    helper.assertFalse(
        remainder.rightClick(player, InteractionHand.MAIN_HAND),
        "an immediate second harvest must not succeed");
    helper.assertValueEqual(
        cropAt(helper, CROP_POS).getCurrentAge(),
        2,
        "slime plant age after the rejected second harvest");

    helper.succeed();
  }

  // an unattended empty crop stick has a 1% chance per crop tick to grow weed
  @GameTest(template = EMPTY)
  public static void emptyCropStickEventuallyGrowsWeed(GameTestHelper helper) {
    placeCropStick(helper, CROP_POS);

    for (int i = 0; i < RNG_ATTEMPTS; i++) {
      TileEntityCrop crop = cropAt(helper, CROP_POS);
      if (crop.getCrop() != null) {
        break;
      }

      crop.performTick();
    }

    TileEntityCrop weed = cropAt(helper, CROP_POS);
    helper.assertTrue(
        weed.getCrop() == Crops.weed, "empty crop stick should grow weed, got " + weed.getCrop());
    helper.assertBlockPresent(Ic2Blocks.WEED_CROP, CROP_POS);
    helper.assertValueEqual(weed.getCurrentAge(), 0, "fresh weed age");

    helper.succeed();
  }

  // stored Weed-EX prevents weed growth entirely and is slowly used up
  @GameTest(template = EMPTY)
  public static void weedExSuppressesWeedGrowth(GameTestHelper helper) {
    TileEntityCrop stick = placeCropStick(helper, CROP_POS);
    stick.setStorageWeedEX(150);

    for (int i = 0; i < 300; i++) {
      stick.performTick();
    }

    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == null, "Weed-EX must prevent weed growth");
    int left = stick.getStorageWeedEX();
    helper.assertTrue(left > 0 && left < 150, "Weed-EX should be partially used up, has " + left);

    helper.succeed();
  }

  // weed work converts a neighbouring crop stick into weed, inheriting the higher growth stat
  // (optionally +1); with sticks on all four sides a single call always converts one
  @GameTest(template = EMPTY)
  public static void weedSpreadsToNeighbouringCropStick(GameTestHelper helper) {
    BlockPos[] sides = {CROP_POS.north(), CROP_POS.south(), CROP_POS.east(), CROP_POS.west()};
    TileEntityCrop weed = plant(helper, CROP_POS, Crops.weed, 1, 20, 0, 0);
    for (BlockPos side : sides) {
      placeCropStick(helper, side);
    }

    weed.performWeedWork();

    TileEntityCrop converted = null;
    for (BlockPos side : sides) {
      TileEntityCrop neighbour = cropAt(helper, side);
      if (neighbour.getCrop() != null) {
        helper.assertTrue(
            converted == null, "weed work should only convert a single neighbour per call");
        converted = neighbour;
      }
    }

    helper.assertTrue(converted != null, "weed work should convert one neighbouring crop stick");
    helper.assertTrue(
        converted.getCrop() == Crops.weed,
        "converted neighbour should hold weed, got " + converted.getCrop());
    int growth = converted.getStatGrowth();
    helper.assertTrue(
        growth == 20 || growth == 21,
        "spread weed inherits the max growth stat (+1 at most), got " + growth);

    helper.succeed();
  }

  // a neighbour with stored Weed-EX never gets overgrown by weed work
  @GameTest(template = EMPTY)
  public static void weedExProtectsNeighbourFromWeedSpread(GameTestHelper helper) {
    TileEntityCrop weed = plant(helper, CROP_POS, Crops.weed, 1, 20, 0, 0);
    TileEntityCrop wheat = plant(helper, CROP_POS.west(), Ic2Crops.cropWheat, 1, 0, 0, 0);
    helper.setBlock(CROP_POS.north(), Blocks.STONE);
    helper.setBlock(CROP_POS.south(), Blocks.STONE);
    helper.setBlock(CROP_POS.east(), Blocks.STONE);

    for (int i = 0; i < 300; i++) {
      wheat.setStorageWeedEX(100);
      weed.performWeedWork();
      if (cropAt(helper, CROP_POS.west()).getCrop() != Ic2Crops.cropWheat) {
        break;
      }
    }

    TileEntityCrop survivor = cropAt(helper, CROP_POS.west());
    helper.assertTrue(
        survivor.getCrop() == Ic2Crops.cropWheat,
        "Weed-EX protected crop must survive weed spread, got " + survivor.getCrop());
    helper.assertValueEqual(survivor.getCurrentAge(), 1, "protected crop age");

    helper.succeed();
  }

  // a crossing base with four mature parents eventually breeds a new crop whose stats are the
  // parents' average with a mutation of at most ±(parent count)
  @GameTest(template = EMPTY)
  public static void crossingBreedsNewCropFromParents(GameTestHelper helper) {
    BlockPos[] parents = {CROP_POS.north(), CROP_POS.south(), CROP_POS.east(), CROP_POS.west()};
    for (BlockPos parentPos : parents) {
      plant(helper, parentPos, Ic2Crops.cropReed, 2, 16, 16, 16);
    }

    placeCropStick(helper, CROP_POS);
    helper.setBlock(
        CROP_POS,
        Ic2Blocks.CROP_STICK.defaultBlockState().setValue(Ic2TileEntityBlock.CROSSING_BASE, true));

    for (int i = 0; i < RNG_ATTEMPTS; i++) {
      TileEntityCrop crossingBase = cropAt(helper, CROP_POS);
      if (crossingBase.getCrop() != null) {
        break;
      }

      // Weed-EX prevents the crossing base from randomly turning into weed while waiting
      crossingBase.setStorageWeedEX(100);
      crossingBase.performTick();
    }

    TileEntityCrop result = cropAt(helper, CROP_POS);
    helper.assertTrue(result.getCrop() != null, "crossing should eventually breed a crop");
    helper.assertValueEqual(result.getCurrentAge(), 0, "bred crop age");
    int[] stats = {result.getStatGrowth(), result.getStatGain(), result.getStatResistance()};
    for (int stat : stats) {
      helper.assertTrue(
          stat >= 12 && stat <= 20, "bred stat must stay within parent average 16 ±4, got " + stat);
    }

    for (BlockPos parentPos : parents) {
      TileEntityCrop parent = cropAt(helper, parentPos);
      helper.assertTrue(
          parent.getCrop() == Ic2Crops.cropReed, "crossing must not consume the parents");
      helper.assertValueEqual(parent.getCurrentAge(), 2, "parent age after crossing");
    }

    helper.succeed();
  }

  // a crossing base with exactly one mature neighbour is claimed by spreading instead, which
  // clones the neighbour's crop and stats verbatim
  @GameTest(template = EMPTY)
  public static void spreadingClonesSingleNeighbour(GameTestHelper helper) {
    plant(helper, CROP_POS.west(), Ic2Crops.cropReed, 2, 10, 7, 5);
    placeCropStick(helper, CROP_POS);
    helper.setBlock(
        CROP_POS,
        Ic2Blocks.CROP_STICK.defaultBlockState().setValue(Ic2TileEntityBlock.CROSSING_BASE, true));

    for (int i = 0; i < RNG_ATTEMPTS; i++) {
      TileEntityCrop crossingBase = cropAt(helper, CROP_POS);
      if (crossingBase.getCrop() != null) {
        break;
      }

      crossingBase.setStorageWeedEX(100);
      crossingBase.performTick();
    }

    TileEntityCrop result = cropAt(helper, CROP_POS);
    helper.assertTrue(
        result.getCrop() == Ic2Crops.cropReed,
        "spreading should clone the single reed neighbour, got " + result.getCrop());
    helper.assertValueEqual(result.getCurrentAge(), 0, "spread crop age");
    helper.assertValueEqual(result.getStatGrowth(), 10, "spread growth stat");
    helper.assertValueEqual(result.getStatGain(), 7, "spread gain stat");
    helper.assertValueEqual(result.getStatResistance(), 5, "spread resistance stat");

    helper.succeed();
  }

  // with good terrain stats a crop gains growth points each tick and ages up once the crop's
  // growth duration is reached, consuming stored water and nutrients along the way
  @GameTest(template = EMPTY)
  public static void growthTickAdvancesAgeInGoodConditions(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropTerraWart, 0, 0, 0, 0);
    crop.setTerrainHumidity(10);
    crop.setTerrainNutrients(10);
    crop.setTerrainAirQuality(5);
    crop.setStorageWater(10);
    crop.setStorageNutrients(10);
    // terra wart is tier 5, so its growth duration is 5 * 200 = 1000 points
    helper.assertValueEqual(
        Ic2Crops.cropTerraWart.getGrowthDuration(crop), 1000, "terra wart growth duration");
    crop.setGrowthPoints(999);

    crop.performTick();

    TileEntityCrop grown = cropAt(helper, CROP_POS);
    helper.assertValueEqual(
        grown.getCurrentAge(), 1, "crop age after crossing the growth duration");
    helper.assertValueEqual(grown.getGrowthPoints(), 0, "growth points reset after aging up");
    helper.assertValueEqual(grown.getStorageWater(), 9, "stored water after one tick");
    helper.assertValueEqual(grown.getStorageNutrients(), 9, "stored nutrients after one tick");

    helper.succeed();
  }

  // a high-tier crop with maxed stats needs far better terrain than bare farmland provides;
  // with resistance 31 it survives but gains no growth points at all
  @GameTest(template = EMPTY)
  public static void hostileConditionsStallResistantCrop(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropTerraWart, 0, 31, 31, 31);
    crop.setTerrainHumidity(0);
    crop.setTerrainNutrients(0);
    crop.setTerrainAirQuality(0);

    for (int i = 0; i < 40; i++) {
      crop.performTick();
    }

    TileEntityCrop stalled = cropAt(helper, CROP_POS);
    helper.assertTrue(
        stalled.getCrop() == Ic2Crops.cropTerraWart,
        "resistant crop must survive hostile conditions");
    helper.assertValueEqual(stalled.getCurrentAge(), 0, "stalled crop age");
    helper.assertValueEqual(stalled.getGrowthPoints(), 0, "stalled crop growth points");

    helper.succeed();
  }

  // the same overstressed crop without resistance dies and leaves an empty crop stick behind
  @GameTest(template = EMPTY)
  public static void hostileConditionsKillUnresistantCrop(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropTerraWart, 0, 31, 31, 0);
    crop.setTerrainHumidity(0);
    crop.setTerrainNutrients(0);
    crop.setTerrainAirQuality(0);

    for (int i = 0; i < 300; i++) {
      TileEntityCrop current = cropAt(helper, CROP_POS);
      if (current.getCrop() == null) {
        break;
      }

      current.performTick();
    }

    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == null,
        "unresistant crop should die in hostile conditions");
    helper.assertBlockPresent(Ic2Blocks.CROP_STICK, CROP_POS);
    helper.assertFalse(
        helper.getBlockState(CROP_POS).getValue(Ic2TileEntityBlock.CROSSING_BASE),
        "dead crop must not leave a crossing base");

    helper.succeed();
  }

  // terrain humidity reacts to stored water (+2 above 5, +1 per 25) and farmland moisture (+2);
  // terrain nutrients react to stored fertilizer (+1 per 20). Diffs are asserted so the
  // biome bonus of the test world cancels out.
  @GameTest(template = EMPTY)
  public static void terrainStatsFollowStorageAndMoisture(GameTestHelper helper) {
    TileEntityCrop crop = placeCropStick(helper, CROP_POS);
    helper.setBlock(
        CROP_POS.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));

    crop = cropAt(helper, CROP_POS);
    crop.setStorageWater(0);
    crop.updateTerrainHumidity();
    int humidityDry = crop.getTerrainHumidity();

    crop.setStorageWater(200);
    crop.updateTerrainHumidity();
    int humidityWet = crop.getTerrainHumidity();
    helper.assertValueEqual(humidityWet - humidityDry, 10, "humidity gain from 200 stored water");

    helper.setBlock(
        CROP_POS.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
    crop = cropAt(helper, CROP_POS);
    crop.setStorageWater(200);
    crop.updateTerrainHumidity();
    helper.assertValueEqual(
        crop.getTerrainHumidity() - humidityWet, 2, "humidity gain from hydrated farmland");

    crop.setStorageNutrients(0);
    crop.updateTerrainNutrients();
    int nutrientsPoor = crop.getTerrainNutrients();

    crop.setStorageNutrients(100);
    crop.updateTerrainNutrients();
    helper.assertValueEqual(
        crop.getTerrainNutrients() - nutrientsPoor, 5, "nutrient gain from 100 stored fertilizer");

    helper.succeed();
  }

  // humidity updates must tolerate a crop stick whose supporting farmland was replaced; a
  // non-farmland block contributes no moisture bonus, just like dry farmland
  @GameTest(template = EMPTY)
  public static void humidityUpdateIgnoresNonFarmlandBelow(GameTestHelper helper) {
    helper.setBlock(CROP_POS.below(), Blocks.DIRT);
    helper.setBlock(
        CROP_POS,
        Ic2Blocks.CROP_STICK.defaultBlockState().setValue(Ic2TileEntityBlock.CROSSING_BASE, false));
    TileEntityCrop crop = cropAt(helper, CROP_POS);
    crop.setStorageWater(0);

    crop.updateTerrainHumidity();
    int dirtHumidity = crop.getTerrainHumidity();

    helper.setBlock(
        CROP_POS.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
    crop = cropAt(helper, CROP_POS);
    crop.setStorageWater(0);
    crop.updateTerrainHumidity();
    helper.assertValueEqual(
        dirtHumidity, crop.getTerrainHumidity(), "humidity above dirt compared to dry farmland");

    helper.succeed();
  }

  // air quality drops by 2 when the 2x2 neighbourhood is walled in and by 4 without sky access
  @GameTest(template = EMPTY)
  public static void airQualityRespondsToEnclosureAndSky(GameTestHelper helper) {
    BlockPos pos = new BlockPos(1, 1, 1);
    TileEntityCrop crop = placeCropStick(helper, pos);

    crop.updateTerrainAirQuality();
    int open = crop.getTerrainAirQuality();

    helper.setBlock(pos.offset(-1, 0, -1), Blocks.STONE);
    helper.setBlock(pos.offset(-1, 0, 0), Blocks.STONE);
    helper.setBlock(pos.offset(0, 0, -1), Blocks.STONE);
    crop = cropAt(helper, pos);
    crop.updateTerrainAirQuality();
    int enclosed = crop.getTerrainAirQuality();
    helper.assertValueEqual(open - enclosed, 2, "air quality loss from a walled-in neighbourhood");

    helper.setBlock(pos.above(), Blocks.STONE);
    // give the light engine a few ticks to update the sky light under the cover
    helper.runAtTickTime(
        10,
        () -> {
          TileEntityCrop covered = cropAt(helper, pos);
          covered.updateTerrainAirQuality();
          helper.assertValueEqual(
              enclosed - covered.getTerrainAirQuality(),
              4,
              "air quality loss from losing sky access");
          helper.succeed();
        });
  }

  // water storage caps at 200, manual Weed-EX at 100 (automated topping up to 150), manual
  // fertilizer adds 100 nutrients but is rejected once at least 100 are stored
  @GameTest(template = EMPTY)
  public static void storageCapsForWaterFertilizerAndWeedEx(GameTestHelper helper) {
    TileEntityCrop crop = placeCropStick(helper, CROP_POS);

    helper.assertValueEqual(
        crop.applyHydration(500, false), 200, "hydration accepted into an empty crop");
    helper.assertValueEqual(crop.getStorageWater(), 200, "water storage cap");
    helper.assertValueEqual(
        crop.applyHydration(1, false), 0, "hydration accepted into a full crop");

    helper.assertValueEqual(
        crop.applyWeedEx(500, false, true, false), 100, "manually applied Weed-EX cap");
    helper.assertValueEqual(
        crop.applyWeedEx(500, false, false, false), 50, "automated Weed-EX topping up to 150");
    helper.assertValueEqual(crop.getStorageWeedEX(), 150, "Weed-EX storage cap");

    helper.assertTrue(crop.applyFertilizer(true), "fertilizing an empty crop should succeed");
    helper.assertValueEqual(
        crop.getStorageNutrients(), 100, "nutrients from one manual fertilizer");
    helper.assertFalse(crop.applyFertilizer(true), "fertilizing a full crop must fail");

    helper.succeed();
  }

  // the cropnalyzer scans seed bags one level at a time, using progressively more energy, and
  // passes fully scanned seeds through for free
  @GameTest(template = EMPTY)
  public static void cropnalyzerScansSeedBagsAndUsesEnergy(GameTestHelper helper) {
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    ItemStack analyzerStack = ElectricItemManager.getCharged(Ic2Items.CROPNALYZER, 1000.0);
    player.setItemInHand(InteractionHand.MAIN_HAND, analyzerStack);
    HandHeldCropAnalyzer analyzer =
        new HandHeldCropAnalyzer(player, InteractionHand.MAIN_HAND, analyzerStack);

    ItemStack seed = ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 2, 3, 4, 0);
    analyzer.setItem(HandHeldCropAnalyzer.SLOT_INPUT, seed);
    analyzer.tryScan();

    ItemStack scanned = analyzer.getItem(HandHeldCropAnalyzer.SLOT_OUTPUT);
    helper.assertFalse(scanned.isEmpty(), "scanned seed should move to the output slot");
    helper.assertTrue(
        analyzer.getItem(HandHeldCropAnalyzer.SLOT_INPUT).isEmpty(),
        "input slot should be emptied");
    ICropSeed seedItem = (ICropSeed) scanned.getItem();
    helper.assertValueEqual(
        seedItem.getScannedFromStack(scanned), 1, "scan level after the first scan");
    helper.assertValueEqual(
        seedItem.getGrowthFromStack(scanned), 2, "growth stat is untouched by scanning");
    // scanning level 0 costs 10 EU. save() re-seats the (possibly replaced) container stack in
    // the player's inventory, so the charge must be read back from the hand.
    Ic2GameTestAssertions.assertNear(
        helper,
        ElectricItem.manager.getCharge(player.getItemInHand(InteractionHand.MAIN_HAND)),
        990.0,
        "charge after the first scan");

    analyzer.setItem(HandHeldCropAnalyzer.SLOT_OUTPUT, StackUtil.emptyStack);
    analyzer.setItem(HandHeldCropAnalyzer.SLOT_INPUT, scanned);
    analyzer.tryScan();
    scanned = analyzer.getItem(HandHeldCropAnalyzer.SLOT_OUTPUT);
    helper.assertValueEqual(
        seedItem.getScannedFromStack(scanned), 2, "scan level after the second scan");
    // scanning level 1 costs 90 EU
    Ic2GameTestAssertions.assertNear(
        helper,
        ElectricItem.manager.getCharge(player.getItemInHand(InteractionHand.MAIN_HAND)),
        900.0,
        "charge after the second scan");

    ItemStack fullyScanned =
        ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 2, 3, 4, 4);
    analyzer.setItem(HandHeldCropAnalyzer.SLOT_OUTPUT, StackUtil.emptyStack);
    analyzer.setItem(HandHeldCropAnalyzer.SLOT_INPUT, fullyScanned);
    analyzer.tryScan();
    ItemStack passed = analyzer.getItem(HandHeldCropAnalyzer.SLOT_OUTPUT);
    helper.assertValueEqual(
        ((ICropSeed) passed.getItem()).getScannedFromStack(passed),
        4,
        "fully scanned seed keeps its scan level");
    Ic2GameTestAssertions.assertNear(
        helper,
        ElectricItem.manager.getCharge(player.getItemInHand(InteractionHand.MAIN_HAND)),
        900.0,
        "fully scanned seeds must not use energy");

    helper.succeed();
  }

  // Fully analyzed seed bags expose all identity and stat data, so identical bags may share an
  // inventory stack. Unanalyzed bags and analyzed bags with different stats must stay separate.
  @GameTest(template = EMPTY)
  public static void fullyAnalyzedSeedBagsWithMatchingStatsStack(GameTestHelper helper) {
    ItemStack first = ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 2, 3, 4, 4);
    ItemStack matching = ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 2, 3, 4, 4);
    ItemStack differentStats =
        ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 5, 3, 4, 4);
    ItemStack unanalyzed = ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 2, 3, 4, 3);

    helper.assertValueEqual(first.getMaxStackSize(), 64, "fully analyzed seed stack limit");
    helper.assertValueEqual(unanalyzed.getMaxStackSize(), 1, "unanalyzed seed stack limit");

    ItemStackHandler analyzedSlot = new ItemStackHandler(1);
    helper.assertTrue(
        analyzedSlot.insertItem(0, first, false).isEmpty(),
        "first fully analyzed seed should enter an empty slot");
    helper.assertTrue(
        analyzedSlot.insertItem(0, matching, false).isEmpty(),
        "fully analyzed seed with matching stats should merge");
    helper.assertValueEqual(
        analyzedSlot.getStackInSlot(0).getCount(), 2, "stacked fully analyzed seeds");
    helper.assertFalse(
        analyzedSlot.insertItem(0, differentStats, false).isEmpty(),
        "fully analyzed seeds with different stats must not merge");

    ItemStackHandler unanalyzedSlot = new ItemStackHandler(1);
    unanalyzedSlot.insertItem(0, unanalyzed, false);
    ItemStack secondUnanalyzed =
        ItemCropSeed.generateItemStackFromValues(Ic2Crops.cropWheat, 2, 3, 4, 3);
    helper.assertFalse(
        unanalyzedSlot.insertItem(0, secondUnanalyzed, false).isEmpty(),
        "unanalyzed seeds must remain unstackable");
    helper.succeed();
  }

  // the weeding trowel clears weed (dropping one weed item per age step + 1) but ignores crops
  @GameTest(template = EMPTY)
  public static void weedingTrowelRemovesOnlyWeeds(GameTestHelper helper) {
    plant(helper, CROP_POS, Crops.weed, 2, 0, 0, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    ItemStack trowel = new ItemStack(Ic2Items.WEEDING_TROWEL);
    player.setItemInHand(InteractionHand.MAIN_HAND, trowel);
    ItemWeedingTrowel item = (ItemWeedingTrowel) Ic2Items.WEEDING_TROWEL;

    InteractionResult result = item.onItemUseFirst(trowel, useContextOn(helper, player, CROP_POS));
    helper.assertTrue(
        result == InteractionResult.SUCCESS, "trowel should clear weed, got " + result);
    helper.assertBlockPresent(Ic2Blocks.CROP_STICK, CROP_POS);
    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == null, "trowelled crop stick should be empty");
    helper.assertItemEntityPresent(Ic2Items.WEED, CROP_POS, 2.0);

    TileEntityCrop wheat = plant(helper, CROP_POS.west(), Ic2Crops.cropWheat, 3, 0, 0, 0);
    result = item.onItemUseFirst(trowel, useContextOn(helper, player, CROP_POS.west()));
    helper.assertTrue(
        result == InteractionResult.PASS, "trowel must ignore non-weed crops, got " + result);
    helper.assertTrue(
        cropAt(helper, CROP_POS.west()).getCrop() == Ic2Crops.cropWheat,
        "crop must survive the trowel");

    helper.succeed();
  }

  // weed takeover must not consume the second crop stick: after the weed is removed, the empty
  // sticks are still a crossing base and left-clicking refunds that second stick
  @GameTest(template = EMPTY)
  public static void weedDoesNotConsumeSecondCropStick(GameTestHelper helper) {
    TileEntityCrop crossingBase = placeCropStick(helper, CROP_POS);
    crossingBase.setCrossingBase(true);
    TileEntityCrop weed = crossingBase.transformCropBlock(Crops.weed, 0);

    // Simulate a save/load while the block is weed, whose blockstate cannot carry crossing_base.
    CompoundTag saved = weed.saveWithoutMetadata(helper.getLevel().registryAccess());
    helper.setBlock(CROP_POS, Blocks.AIR);
    helper.setBlock(CROP_POS, Ic2Blocks.WEED_CROP.defaultBlockState());
    cropAt(helper, CROP_POS).loadWithComponents(saved, helper.getLevel().registryAccess());

    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    ItemStack trowel = new ItemStack(Ic2Items.WEEDING_TROWEL);
    player.setItemInHand(InteractionHand.MAIN_HAND, trowel);
    InteractionResult result =
        ((ItemWeedingTrowel) Ic2Items.WEEDING_TROWEL)
            .onItemUseFirst(trowel, useContextOn(helper, player, CROP_POS));

    helper.assertTrue(
        result == InteractionResult.SUCCESS, "trowel should clear weed, got " + result);
    helper.assertTrue(
        cropAt(helper, CROP_POS).isCrossingBase(), "removing weed must restore the crossing base");
    helper.assertTrue(
        helper.getBlockState(CROP_POS).getValue(Ic2TileEntityBlock.CROSSING_BASE),
        "restored crop-stick block state should remain a crossing base");

    Ic2TileEntityBlock cropStickBlock = (Ic2TileEntityBlock) Ic2Blocks.CROP_STICK;
    result =
        cropStickBlock.startBreak(
            player,
            helper.getLevel(),
            InteractionHand.MAIN_HAND,
            helper.absolutePos(CROP_POS),
            helper.getBlockState(CROP_POS),
            Direction.UP);
    helper.assertTrue(
        result == InteractionResult.SUCCESS, "left-clicking the crossing base should succeed");
    helper.assertFalse(
        cropAt(helper, CROP_POS).isCrossingBase(),
        "left-clicking should remove only the second crop stick");
    helper.assertItemEntityPresent(Ic2Items.CROP_STICK, CROP_POS, 2.0);

    helper.succeed();
  }

  // the crop harvester scans its 9x3x9 working area and harvests crops at their optimal age
  // into its inventory. The scan pointer is re-aimed at the crop each tick and the crop kept
  // mature, because a single harvest may roll zero drops.
  @GameTest(template = EMPTY, timeoutTicks = 400)
  public static void cropHarvesterCollectsMatureCropGains(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos cropPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROP_HARVESTER);
    TileEntityCropHarvester te = (TileEntityCropHarvester) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));

    plant(helper, cropPos, Ic2Crops.cropWheat, 7, 0, 31, 0);

    helper.onEachTick(
        () -> {
          // keep the scan aimed one step before the crop's (-1, 0, 0) offset and the wheat mature
          te.scanX = -2;
          te.scanY = 0;
          te.scanZ = 0;
          TileEntityCrop crop = cropAt(helper, cropPos);
          if (crop.getCrop() != null) {
            crop.setCurrentAge(7);
          }
        });

    helper.succeedWhen(
        () -> {
          if (!slotContains(te.contentSlot, Items.WHEAT)) {
            helper.assertItemEntityPresent(Items.WHEAT, cropPos, 3.0);
          }
        });
  }

  // the cropmatron applies fertilizer (+90 nutrients), water (up to 200) and Weed-EX (up to 150)
  // to crops in its working area
  @GameTest(template = EMPTY, timeoutTicks = 200)
  public static void cropmatronFertilizesHydratesAndAppliesWeedEx(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos cropPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROPMATRON);
    TileEntityCropmatron te = (TileEntityCropmatron) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));
    te.fertilizerSlot.put(0, new ItemStack(Ic2Items.FERTILIZER));
    helper.assertValueEqual(
        te.getWaterTank()
            .fillMb(
                Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false),
        1000,
        "water accepted by the cropmatron");
    helper.assertValueEqual(
        te.getExTank().fillMb(Ic2FluidStack.create(Ic2Fluids.WEED_EX.still(), 1000), false),
        1000,
        "Weed-EX accepted by the cropmatron");

    plant(helper, cropPos, Ic2Crops.cropWheat, 1, 0, 0, 0);

    // aim the scan one step before the crop's (-1, 0, 0) offset
    te.scanX = -2;
    te.scanY = 0;
    te.scanZ = 0;

    helper.succeedWhen(
        () -> {
          TileEntityCrop crop = cropAt(helper, cropPos);
          // the crop's own tick may already have consumed a point or two of what was applied
          helper.assertTrue(
              crop.getStorageNutrients() >= 85,
              "cropmatron should fertilize the crop, has " + crop.getStorageNutrients());
          helper.assertTrue(
              crop.getStorageWater() >= 195,
              "cropmatron should hydrate the crop, has " + crop.getStorageWater());
          helper.assertTrue(
              crop.getStorageWeedEX() >= 140,
              "cropmatron should apply Weed-EX, has " + crop.getStorageWeedEX());
          helper.assertTrue(te.fertilizerSlot.get(0).isEmpty(), "fertilizer should be consumed");
          helper.assertValueEqual(
              te.getWaterTank().getFluidAmount(), 800, "water left in the cropmatron tank");
          helper.assertValueEqual(
              te.getExTank().getFluidAmount(), 850, "Weed-EX left in the cropmatron tank");
        });
  }

  // the cropmatron also re-hydrates dry farmland in its working area from its water tank
  @GameTest(template = EMPTY, timeoutTicks = 200)
  public static void cropmatronHydratesNearbyFarmland(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos farmlandPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROPMATRON);
    TileEntityCropmatron te = (TileEntityCropmatron) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));
    helper.assertValueEqual(
        te.getWaterTank()
            .fillMb(
                Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false),
        1000,
        "water accepted by the cropmatron");

    helper.setBlock(
        farmlandPos, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
    te.scanX = -2;
    te.scanY = 0;
    te.scanZ = 0;

    helper.succeedWhen(
        () -> {
          helper.assertValueEqual(
              helper.getBlockState(farmlandPos).getValue(FarmBlock.MOISTURE),
              7,
              "farmland moisture after the cropmatron pass");
          helper.assertValueEqual(
              te.getWaterTank().getFluidAmount(),
              993,
              "1 mB per moisture point drained from the tank");
        });
  }

  // torchflower seeds (1.20 sniffer flower) are a registered base seed: right-clicking an empty
  // crop stick plants the torchflower crop at age 0 with base stats and consumes one item
  @GameTest(template = EMPTY)
  public static void torchflowerSeedsPlantTorchflowerCrop(GameTestHelper helper) {
    TileEntityCrop stick = placeCropStick(helper, CROP_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCHFLOWER_SEEDS, 2));

    helper.assertTrue(
        stick.rightClick(player, InteractionHand.MAIN_HAND),
        "planting torchflower seeds should succeed");

    TileEntityCrop planted = cropAt(helper, CROP_POS);
    helper.assertTrue(
        planted.getCrop() == Ic2Crops.cropTorchflower,
        "torchflower seeds should plant the torchflower crop, got " + planted.getCrop());
    helper.assertBlockPresent(Ic2Blocks.TORCHFLOWER_CROP, CROP_POS);
    helper.assertValueEqual(planted.getCurrentAge(), 0, "freshly planted torchflower age");
    helper.assertValueEqual(planted.getStatGrowth(), 1, "planted growth stat");
    helper.assertValueEqual(planted.getStatGain(), 1, "planted gain stat");
    helper.assertValueEqual(planted.getStatResistance(), 1, "planted resistance stat");
    helper.assertValueEqual(
        player.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "seeds left after planting");

    helper.succeed();
  }

  // a pitcher pod (1.20 sniffer flower) plants the pitcher plant crop the same way
  @GameTest(template = EMPTY)
  public static void pitcherPodPlantsPitcherPlantCrop(GameTestHelper helper) {
    TileEntityCrop stick = placeCropStick(helper, CROP_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PITCHER_POD, 2));

    helper.assertTrue(
        stick.rightClick(player, InteractionHand.MAIN_HAND),
        "planting a pitcher pod should succeed");

    TileEntityCrop planted = cropAt(helper, CROP_POS);
    helper.assertTrue(
        planted.getCrop() == Ic2Crops.cropPitcherPlant,
        "a pitcher pod should plant the pitcher plant crop, got " + planted.getCrop());
    helper.assertBlockPresent(Ic2Blocks.PITCHER_PLANT_CROP, CROP_POS);
    helper.assertValueEqual(planted.getCurrentAge(), 0, "freshly planted pitcher plant age");
    helper.assertValueEqual(
        player.getItemInHand(InteractionHand.MAIN_HAND).getCount(), 1, "pods left after planting");

    helper.succeed();
  }

  // a crossing base must reject the sniffer flower seeds like any other base seed
  @GameTest(template = EMPTY)
  public static void crossingBaseRejectsSnifferFlowerSeeds(GameTestHelper helper) {
    placeCropStick(helper, CROP_POS);
    helper.setBlock(
        CROP_POS,
        Ic2Blocks.CROP_STICK.defaultBlockState().setValue(Ic2TileEntityBlock.CROSSING_BASE, true));
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCHFLOWER_SEEDS));
    helper.assertFalse(
        cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND),
        "a crossing base must not accept torchflower seeds");

    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PITCHER_POD));
    helper.assertFalse(
        cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND),
        "a crossing base must not accept pitcher pods");

    helper.assertTrue(
        cropAt(helper, CROP_POS).getCrop() == null, "crossing base should still be empty");

    helper.succeed();
  }

  // an immature torchflower (below its age-2 harvest threshold) must not be harvestable
  @GameTest(template = EMPTY)
  public static void immatureTorchflowerCannotBeHarvested(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropTorchflower, 1, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertFalse(
        Ic2Crops.cropTorchflower.canBeHarvested(crop),
        "an age-1 torchflower must not be harvestable");
    helper.assertFalse(
        crop.rightClick(player, InteractionHand.MAIN_HAND),
        "right-clicking an immature torchflower must not harvest");
    helper.assertItemEntityNotPresent(Items.TORCHFLOWER, CROP_POS, 2.0);
    helper.assertValueEqual(
        cropAt(helper, CROP_POS).getCurrentAge(), 1, "torchflower age after the rejected harvest");

    helper.succeed();
  }

  // an immature pitcher plant (below its age-4 harvest threshold) must not be harvestable
  @GameTest(template = EMPTY)
  public static void immaturePitcherPlantCannotBeHarvested(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropPitcherPlant, 3, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertFalse(
        Ic2Crops.cropPitcherPlant.canBeHarvested(crop),
        "an age-3 pitcher plant must not be harvestable");
    helper.assertFalse(
        crop.rightClick(player, InteractionHand.MAIN_HAND),
        "right-clicking an immature pitcher plant must not harvest");
    helper.assertItemEntityNotPresent(Items.PITCHER_PLANT, CROP_POS, 2.0);
    helper.assertValueEqual(
        cropAt(helper, CROP_POS).getCurrentAge(),
        3,
        "pitcher plant age after the rejected harvest");

    helper.succeed();
  }

  // right-click harvesting a mature torchflower drops torchflowers and resets the crop to age 1,
  // below the harvest threshold, so an immediate second harvest must fail
  @GameTest(template = EMPTY)
  public static void matureTorchflowerHarvestDropsTorchflower(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropTorchflower, 2, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertTrue(
        Ic2Crops.cropTorchflower.canBeHarvested(crop),
        "a torchflower at max age should be harvestable");

    // a single harvest may roll zero drops, so re-mature and retry until one drops
    boolean harvested = false;
    for (int i = 0; i < RNG_ATTEMPTS && !harvested; i++) {
      cropAt(helper, CROP_POS).setCurrentAge(2);
      harvested = cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND);
    }

    helper.assertTrue(harvested, "right-clicking a mature torchflower should drop torchflowers");
    helper.assertItemEntityPresent(Items.TORCHFLOWER, CROP_POS, 2.0);

    TileEntityCrop remainder = cropAt(helper, CROP_POS);
    helper.assertTrue(
        remainder.getCrop() == Ic2Crops.cropTorchflower,
        "harvesting must not destroy the torchflower crop");
    helper.assertValueEqual(remainder.getCurrentAge(), 1, "torchflower age after harvest");
    helper.assertFalse(
        remainder.rightClick(player, InteractionHand.MAIN_HAND),
        "an immediate second harvest must not succeed");

    helper.succeed();
  }

  // right-click harvesting a mature pitcher plant drops pitcher plants and resets the crop to
  // age 1
  @GameTest(template = EMPTY)
  public static void maturePitcherPlantHarvestDropsPitcherPlant(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropPitcherPlant, 4, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertTrue(
        Ic2Crops.cropPitcherPlant.canBeHarvested(crop),
        "a pitcher plant at max age should be harvestable");

    boolean harvested = false;
    for (int i = 0; i < RNG_ATTEMPTS && !harvested; i++) {
      cropAt(helper, CROP_POS).setCurrentAge(4);
      harvested = cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND);
    }

    helper.assertTrue(
        harvested, "right-clicking a mature pitcher plant should drop pitcher plants");
    helper.assertItemEntityPresent(Items.PITCHER_PLANT, CROP_POS, 2.0);

    TileEntityCrop remainder = cropAt(helper, CROP_POS);
    helper.assertTrue(
        remainder.getCrop() == Ic2Crops.cropPitcherPlant,
        "harvesting must not destroy the pitcher plant crop");
    helper.assertValueEqual(remainder.getCurrentAge(), 1, "pitcher plant age after harvest");
    helper.assertFalse(
        remainder.rightClick(player, InteractionHand.MAIN_HAND),
        "an immediate second harvest must not succeed");

    helper.succeed();
  }

  // the crop harvester picks up a torchflower at its optimal harvest age (2) like any other crop
  @GameTest(template = EMPTY, timeoutTicks = 400)
  public static void cropHarvesterCollectsMatureTorchflowers(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos cropPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROP_HARVESTER);
    TileEntityCropHarvester te = (TileEntityCropHarvester) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));

    plant(helper, cropPos, Ic2Crops.cropTorchflower, 2, 0, 31, 0);

    helper.onEachTick(
        () -> {
          // keep the scan aimed one step before the crop's (-1, 0, 0) offset and the flower mature
          te.scanX = -2;
          te.scanY = 0;
          te.scanZ = 0;
          TileEntityCrop crop = cropAt(helper, cropPos);
          if (crop.getCrop() != null) {
            crop.setCurrentAge(2);
          }
        });

    helper.succeedWhen(
        () -> {
          if (!slotContains(te.contentSlot, Items.TORCHFLOWER)) {
            helper.assertItemEntityPresent(Items.TORCHFLOWER, cropPos, 3.0);
          }
        });
  }

  // the crop harvester must leave an immature torchflower alone
  @GameTest(template = EMPTY, timeoutTicks = 250)
  public static void cropHarvesterIgnoresImmatureTorchflower(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos cropPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROP_HARVESTER);
    TileEntityCropHarvester te = (TileEntityCropHarvester) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));

    // resistance 31 keeps the crop alive on bare farmland for the whole observation window (a
    // high gain stat would raise its needs enough to let it die mid-test, see the hostile
    // condition tests); gain does not matter for a test that must harvest nothing
    plant(helper, cropPos, Ic2Crops.cropTorchflower, 1, 0, 0, 31);

    helper.onEachTick(
        () -> {
          // keep the scan aimed at the crop and the flower pinned below the harvest threshold
          te.scanX = -2;
          te.scanY = 0;
          te.scanZ = 0;
          TileEntityCrop crop = cropAt(helper, cropPos);
          if (crop.getCrop() != null) {
            crop.setCurrentAge(1);
          }
        });

    helper.runAtTickTime(
        200,
        () -> {
          helper.assertFalse(
              slotContains(te.contentSlot, Items.TORCHFLOWER),
              "the harvester must not collect an immature torchflower");
          helper.assertItemEntityNotPresent(Items.TORCHFLOWER, cropPos, 3.0);
          helper.assertTrue(
              cropAt(helper, cropPos).getCrop() == Ic2Crops.cropTorchflower,
              "the immature torchflower must survive the harvester");
          helper.succeed();
        });
  }

  // the cropmatron tends a pitcher plant crop: fertilizer, water and Weed-EX all arrive
  @GameTest(template = EMPTY, timeoutTicks = 200)
  public static void cropmatronTendsPitcherPlantCrop(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos cropPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROPMATRON);
    TileEntityCropmatron te = (TileEntityCropmatron) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));
    te.fertilizerSlot.put(0, new ItemStack(Ic2Items.FERTILIZER));
    helper.assertValueEqual(
        te.getWaterTank()
            .fillMb(
                Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false),
        1000,
        "water accepted by the cropmatron");
    helper.assertValueEqual(
        te.getExTank().fillMb(Ic2FluidStack.create(Ic2Fluids.WEED_EX.still(), 1000), false),
        1000,
        "Weed-EX accepted by the cropmatron");

    plant(helper, cropPos, Ic2Crops.cropPitcherPlant, 1, 0, 0, 0);

    // aim the scan one step before the crop's (-1, 0, 0) offset
    te.scanX = -2;
    te.scanY = 0;
    te.scanZ = 0;

    helper.succeedWhen(
        () -> {
          TileEntityCrop crop = cropAt(helper, cropPos);
          // the crop's own tick may already have consumed a point or two of what was applied
          helper.assertTrue(
              crop.getStorageNutrients() >= 85,
              "cropmatron should fertilize the pitcher plant, has " + crop.getStorageNutrients());
          helper.assertTrue(
              crop.getStorageWater() >= 195,
              "cropmatron should hydrate the pitcher plant, has " + crop.getStorageWater());
          helper.assertTrue(
              crop.getStorageWeedEX() >= 140,
              "cropmatron should apply Weed-EX, has " + crop.getStorageWeedEX());
        });
  }

  // every small vanilla dye flower is a base seed for its own color crop; like poppy/dandelion
  // they plant already mature (size 3) and consume one flower
  @GameTest(template = EMPTY)
  public static void dyeFlowerItemsPlantMatureColorCrops(GameTestHelper helper) {
    Object[][] flowers = {
      {Items.CORNFLOWER, Ic2Crops.cropCornflower},
      {Items.LILY_OF_THE_VALLEY, Ic2Crops.cropLilyOfTheValley},
      {Items.WITHER_ROSE, Ic2Crops.cropWitherRose},
      {Items.BLUE_ORCHID, Ic2Crops.cropBlueOrchid},
      {Items.ALLIUM, Ic2Crops.cropAllium},
      {Items.AZURE_BLUET, Ic2Crops.cropAzureBluet},
      {Items.OXEYE_DAISY, Ic2Crops.cropOxeyeDaisy},
      {Items.RED_TULIP, Ic2Crops.cropRedTulip},
      {Items.ORANGE_TULIP, Ic2Crops.cropOrangeTulip},
      {Items.WHITE_TULIP, Ic2Crops.cropWhiteTulip},
      {Items.PINK_TULIP, Ic2Crops.cropPinkTulip},
    };
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    for (Object[] entry : flowers) {
      Item flower = (Item) entry[0];
      CropCard card = (CropCard) entry[1];
      String name = BuiltInRegistries.ITEM.getKey(flower).getPath();

      TileEntityCrop stick = placeCropStick(helper, CROP_POS);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(flower, 2));

      helper.assertTrue(
          stick.rightClick(player, InteractionHand.MAIN_HAND),
          "planting " + name + " should succeed");

      TileEntityCrop planted = cropAt(helper, CROP_POS);
      helper.assertTrue(
          planted.getCrop() == card,
          name + " should plant its color crop, got " + planted.getCrop());
      helper.assertBlockPresent(card.getCropBlock(), CROP_POS);
      helper.assertValueEqual(
          planted.getCurrentAge(), 3, "freshly planted " + name + " crop age (planted mature)");
      helper.assertValueEqual(
          player.getItemInHand(InteractionHand.MAIN_HAND).getCount(),
          1,
          name + " left after planting");
    }

    helper.succeed();
  }

  // an immature dye flower crop (below max age) must not be harvestable
  @GameTest(template = EMPTY)
  public static void immatureDyeFlowerCannotBeHarvested(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropWitherRose, 2, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertFalse(
        Ic2Crops.cropWitherRose.canBeHarvested(crop),
        "an age-2 wither rose crop must not be harvestable");
    helper.assertFalse(
        crop.rightClick(player, InteractionHand.MAIN_HAND),
        "right-clicking an immature wither rose crop must not harvest");
    helper.assertItemEntityNotPresent(Items.BLACK_DYE, CROP_POS, 2.0);
    helper.assertValueEqual(
        cropAt(helper, CROP_POS).getCurrentAge(), 2, "wither rose age after the rejected harvest");

    helper.succeed();
  }

  // harvesting a mature dye flower crop drops its vanilla recipe dye and resets the crop to
  // age 2, below the harvest threshold, so an immediate second harvest must fail
  @GameTest(template = EMPTY)
  public static void matureDyeFlowerHarvestDropsItsDye(GameTestHelper helper) {
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropCornflower, 3, 0, 31, 0);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    helper.assertTrue(
        Ic2Crops.cropCornflower.canBeHarvested(crop),
        "a cornflower crop at max age should be harvestable");

    // a single harvest may roll zero drops, so re-mature and retry until one drops
    boolean harvested = false;
    for (int i = 0; i < RNG_ATTEMPTS && !harvested; i++) {
      cropAt(helper, CROP_POS).setCurrentAge(3);
      harvested = cropAt(helper, CROP_POS).rightClick(player, InteractionHand.MAIN_HAND);
    }

    helper.assertTrue(harvested, "right-clicking a mature cornflower crop should drop dye");
    helper.assertItemEntityPresent(Items.BLUE_DYE, CROP_POS, 2.0);

    TileEntityCrop remainder = cropAt(helper, CROP_POS);
    helper.assertTrue(
        remainder.getCrop() == Ic2Crops.cropCornflower,
        "harvesting must not destroy the cornflower crop");
    helper.assertValueEqual(remainder.getCurrentAge(), 2, "cornflower crop age after harvest");
    helper.assertFalse(
        remainder.rightClick(player, InteractionHand.MAIN_HAND),
        "an immediate second harvest must not succeed");

    helper.succeed();
  }

  // the crop harvester collects the dye gains of a mature dye flower crop
  @GameTest(template = EMPTY, timeoutTicks = 400)
  public static void cropHarvesterCollectsDyeFlowerGains(GameTestHelper helper) {
    BlockPos machinePos = new BlockPos(1, 1, 1);
    BlockPos cropPos = new BlockPos(0, 1, 1);
    helper.setBlock(machinePos, Ic2Blocks.CROP_HARVESTER);
    TileEntityCropHarvester te = (TileEntityCropHarvester) helper.getBlockEntity(machinePos);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));

    plant(helper, cropPos, Ic2Crops.cropCornflower, 3, 0, 31, 31);

    helper.onEachTick(
        () -> {
          // keep the scan aimed one step before the crop's (-1, 0, 0) offset and the flower mature
          te.scanX = -2;
          te.scanY = 0;
          te.scanZ = 0;
          TileEntityCrop crop = cropAt(helper, cropPos);
          if (crop.getCrop() != null) {
            crop.setCurrentAge(3);
          }
        });

    helper.succeedWhen(
        () -> {
          if (!slotContains(te.contentSlot, Items.BLUE_DYE)) {
            helper.assertItemEntityPresent(Items.BLUE_DYE, cropPos, 3.0);
          }
        });
  }

  // eating a terra wart cures negative effects (leaving positive ones alone) and shortens
  // radiation by 600 ticks, removing it entirely when it is short enough
  @GameTest(template = EMPTY)
  public static void terraWartCuresNegativeEffects(GameTestHelper helper) {
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.addEffect(new MobEffectInstance(MobEffects.POISON, 600));
    player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600));
    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
    player.addEffect(new MobEffectInstance(Ic2Potion.radiationHolder(), 1200));
    ItemTerraWart item = (ItemTerraWart) Ic2Items.TERRA_WART;

    item.finishUsingItem(new ItemStack(item), helper.getLevel(), player);

    helper.assertFalse(player.hasEffect(MobEffects.POISON), "terra wart should cure poison");
    helper.assertFalse(player.hasEffect(MobEffects.WEAKNESS), "terra wart should cure weakness");
    helper.assertTrue(
        player.hasEffect(MobEffects.MOVEMENT_SPEED), "terra wart must not remove positive effects");
    MobEffectInstance radiation = player.getEffect(Ic2Potion.radiationHolder());
    helper.assertTrue(
        radiation != null && radiation.getDuration() == 600,
        "terra wart should shorten radiation by 600 ticks, has " + radiation);

    player.addEffect(new MobEffectInstance(Ic2Potion.radiationHolder(), 400));
    item.finishUsingItem(new ItemStack(item), helper.getLevel(), player);
    helper.assertFalse(
        player.hasEffect(Ic2Potion.radiationHolder()),
        "short radiation should be removed entirely");

    helper.succeed();
  }

  // terra wart gains 100 bonus growth points per tick while snow is within its 5-block roots
  @GameTest(template = EMPTY)
  public static void terraWartGrowsFastAboveSnow(GameTestHelper helper) {
    helper.setBlock(new BlockPos(1, 0, 1), Blocks.SNOW);
    TileEntityCrop crop = plant(helper, CROP_POS, Ic2Crops.cropTerraWart, 0, 0, 0, 0);

    Ic2Crops.cropTerraWart.tick(crop);

    helper.assertValueEqual(
        crop.getGrowthPoints(), 100, "growth points from one snow-boosted tick");

    helper.succeed();
  }

  // terra wart rooted above soul sand eventually reverts into a nether wart crop
  @GameTest(template = EMPTY)
  public static void terraWartAboveSoulSandBecomesNetherWart(GameTestHelper helper) {
    helper.setBlock(new BlockPos(1, 0, 1), Blocks.SOUL_SAND);
    plant(helper, CROP_POS, Ic2Crops.cropTerraWart, 0, 0, 0, 0);

    for (int i = 0; i < 30000; i++) {
      TileEntityCrop crop = cropAt(helper, CROP_POS);
      if (crop.getCrop() != Ic2Crops.cropTerraWart) {
        break;
      }

      Ic2Crops.cropTerraWart.tick(crop);
    }

    TileEntityCrop converted = cropAt(helper, CROP_POS);
    helper.assertTrue(
        converted.getCrop() == Ic2Crops.cropNetherWart,
        "terra wart above soul sand should become nether wart, got " + converted.getCrop());

    helper.succeed();
  }
}
