package ic2.core.gametest;

import ic2.api.network.INetworkClientTileEntityEventListener;
import ic2.core.block.comp.Energy;
import ic2.core.block.comp.Fluids;
import ic2.core.block.generator.tileentity.TileEntitySolarGenerator;
import ic2.core.block.machine.tileentity.TileEntityCondenser;
import ic2.core.block.machine.tileentity.TileEntityFluidBottler;
import ic2.core.block.machine.tileentity.TileEntityFluidDistributor;
import ic2.core.block.machine.tileentity.TileEntityFluidRegulator;
import ic2.core.block.machine.tileentity.TileEntitySolarDistiller;
import ic2.core.block.machine.tileentity.TileEntityTank;
import ic2.core.block.tileentity.Ic2TileEntity;
import ic2.core.block.tileentity.Ic2TileEntityBlock;
import ic2.core.fluid.FluidHandler;
import ic2.core.fluid.Ic2FluidStack;
import ic2.core.fluid.Ic2FluidTank;
import ic2.core.item.ElectricItemManager;
import ic2.core.ref.Ic2Blocks;
import ic2.core.ref.Ic2Fluids;
import ic2.core.ref.Ic2Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.commons.lang3.mutable.MutableObject;

@GameTestHolder("ic2")
@PrefixGameTestTemplate(false)
public class FluidMachineGameTests {
  private static final String EMPTY = "gametest/empty3x3x3";

  private static final BlockPos MACHINE_POS = new BlockPos(1, 1, 1);

  // External fluid handlers may inspect stacked containers before deciding whether they can be
  // transferred. IC2 only supports fluid operations on one cell at a time, so the capability must
  // report an empty tank instead of passing the stacked item to ItemClassicCell and throwing.
  @GameTest(template = EMPTY)
  public static void stackedCellsCanBeSafelyInspectedByFluidHandlers(GameTestHelper helper) {
    ItemStack stackedCells = new ItemStack(Ic2Items.WATER_CELL, 2);
    IFluidHandlerItem handler = stackedCells.getCapability(Capabilities.FluidHandler.ITEM);

    helper.assertTrue(handler != null, "water cell should expose an item fluid handler");
    helper.assertTrue(
        handler.getFluidInTank(0).isEmpty(),
        "a stacked fluid container should not expose drainable tank contents");
    helper.succeed();
  }

  // fluid bottler, drain side: a water cell in the top slot is emptied into the internal tank
  @GameTest(template = EMPTY, timeoutTicks = 300)
  public static void fluidBottlerDrainsWaterCellIntoTank(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.FLUID_BOTTLER);
    TileEntityFluidBottler te = getTe(helper, MACHINE_POS, TileEntityFluidBottler.class);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));
    te.drainInputSlot.put(0, new ItemStack(Ic2Items.WATER_CELL));

    helper.succeedWhen(
        () -> {
          assertTankContains(
              helper,
              te.fluidTank,
              net.minecraft.world.level.material.Fluids.WATER,
              1000,
              "fluid bottler tank");
          ItemStack output = te.outputSlot.get(0);
          helper.assertTrue(
              output.getItem() == Ic2Items.EMPTY_CELL && output.getCount() == 1,
              "fluid bottler should return 1 empty cell, has " + output);
          helper.assertTrue(
              te.drainInputSlot.get(0).isEmpty(), "fluid bottler water cell should be consumed");
        });
  }

  // fluid bottler, fill side: an empty cell in the bottom slot is filled from the internal tank
  @GameTest(template = EMPTY, timeoutTicks = 300)
  public static void fluidBottlerFillsEmptyCellFromTank(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.FLUID_BOTTLER);
    TileEntityFluidBottler te = getTe(helper, MACHINE_POS, TileEntityFluidBottler.class);
    te.dischargeSlot.put(
        0, ElectricItemManager.getCharged(Ic2Items.RE_BATTERY, Double.POSITIVE_INFINITY));
    int filled =
        te.fluidTank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false);
    helper.assertValueEqual(filled, 1000, "water accepted by the fluid bottler tank");
    te.fillInputSlot.put(0, new ItemStack(Ic2Items.EMPTY_CELL));

    helper.succeedWhen(
        () -> {
          ItemStack output = te.outputSlot.get(0);
          helper.assertTrue(
              output.getItem() == Ic2Items.WATER_CELL && output.getCount() == 1,
              "fluid bottler should produce 1 water cell, has " + output);
          helper.assertTrue(
              te.fillInputSlot.get(0).isEmpty(), "fluid bottler empty cell should be consumed");
          helper.assertTrue(te.fluidTank.isEmpty(), "fluid bottler water should be consumed");
        });
  }

  // condenser, passive: with no vents it condenses 100 mB steam per tick for free,
  // producing 1 mB distilled water per 100 mB steam.
  @GameTest(template = EMPTY, timeoutTicks = 300)
  public static void condenserCondensesSteamIntoDistilledWater(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.CONDENSER);
    TileEntityCondenser te = getTe(helper, MACHINE_POS, TileEntityCondenser.class);
    int filled =
        te.getInputTank().fillMb(Ic2FluidStack.create(Ic2Fluids.STEAM.still(), 10000), false);
    helper.assertValueEqual(filled, 10000, "steam accepted by the condenser input tank");

    helper.succeedWhen(
        () -> {
          assertTankContains(
              helper,
              te.getOutputTank(),
              Ic2Fluids.DISTILLED_WATER.still(),
              100,
              "condenser output tank");
          helper.assertValueEqual(
              te.getOutputTank().getFluidAmount(), 100, "distilled water from 10000 mB steam");
          helper.assertTrue(te.getInputTank().isEmpty(), "condenser steam should be consumed");
        });
  }

  // condenser, vented: 4 heat vents add 100 mB/t each for 2 EU/t each, so 10000 mB steam
  // drains at 500 mB/t over 20 ticks and costs exactly 160 EU.
  @GameTest(template = EMPTY, timeoutTicks = 150)
  public static void condenserVentsSpeedUpCondensationUsingEnergy(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.CONDENSER);
    TileEntityCondenser te = getTe(helper, MACHINE_POS, TileEntityCondenser.class);
    for (int i = 0; i < te.ventSlots.size(); i++) {
      te.ventSlots.put(i, new ItemStack(Ic2Items.HEAT_VENT));
    }

    Energy energy = te.getComponent(Energy.class);
    energy.addEnergy(1000.0);
    int filled =
        te.getInputTank().fillMb(Ic2FluidStack.create(Ic2Fluids.STEAM.still(), 10000), false);
    helper.assertValueEqual(filled, 10000, "steam accepted by the condenser input tank");

    helper.runAtTickTime(
        100,
        () -> {
          assertTankContains(
              helper,
              te.getOutputTank(),
              Ic2Fluids.DISTILLED_WATER.still(),
              100,
              "condenser output tank");
          helper.assertTrue(te.getInputTank().isEmpty(), "condenser steam should be consumed");
          Ic2GameTestAssertions.assertNear(
              helper,
              energy.getEnergy(),
              1000.0 - 160.0,
              "condenser energy after venting 10000 mB steam");
          helper.succeed();
        });
  }

  // solar distiller: under the noon sun it converts water into distilled water 1 mB at a time
  @GameTest(template = EMPTY, timeoutTicks = 400)
  public static void solarDistillerDistillsWaterInSunlight(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.SOLAR_DISTILLER);
    TileEntitySolarDistiller te = getTe(helper, MACHINE_POS, TileEntitySolarDistiller.class);

    ServerLevel level = helper.getLevel();
    level.setDayTime(6000L);
    level.setWeatherParameters(100000, 0, false, false);
    float skyLight =
        TileEntitySolarGenerator.getSkyLight(level, helper.absolutePos(MACHINE_POS).above());
    helper.assertTrue(
        skyLight > 0.5F, "solar distiller should see the noon sun, sky light is " + skyLight);

    int filled =
        te.inputTank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false);
    helper.assertValueEqual(filled, 1000, "water accepted by the solar distiller input tank");

    helper.succeedWhen(
        () -> {
          assertTankContains(
              helper,
              te.outputTank,
              Ic2Fluids.DISTILLED_WATER.still(),
              1,
              "solar distiller output tank");
          helper.assertValueEqual(
              te.inputTank.getFluidAmount() + te.outputTank.getFluidAmount(),
              1000,
              "solar distiller water to distilled water conversion is 1:1");
        });
  }

  // tank: 24000 mB capacity, excess fluid is rejected and partial drains leave the rest behind
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void tankStoresFluidUpToCapacity(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.TANK);
    Ic2FluidTank tank = getTankBlockTank(helper, MACHINE_POS);
    int filled =
        tank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 30000), false);
    helper.assertValueEqual(filled, 24000, "water accepted by an empty tank");
    int overflow =
        tank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false);
    helper.assertValueEqual(overflow, 0, "water accepted by a full tank");

    Ic2FluidStack drained = tank.drainMb(4000, false);
    helper.assertTrue(
        drained != null
            && drained.getFluid() == net.minecraft.world.level.material.Fluids.WATER
            && drained.getAmountMb() == 4000,
        "tank should drain 4000 mB water, drained " + drained);

    helper.runAtTickTime(
        10,
        () -> {
          assertTankContains(
              helper,
              tank,
              net.minecraft.world.level.material.Fluids.WATER,
              20000,
              "tank after partial drain");
          helper.assertValueEqual(tank.getFluidAmount(), 20000, "tank content after partial drain");
          helper.succeed();
        });
  }

  // tank GUI click, upstream 9817fc3c regression: a full 1000 mB cell must not be consumed
  // (voiding the excess) while the tank has less than 1000 mB of free space
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void tankGuiClickKeepsFullCellWhenTankAlmostFull(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.TANK);
    TileEntityTank te = getTe(helper, MACHINE_POS, TileEntityTank.class);
    Ic2FluidTank tank = getTankBlockTank(helper, MACHINE_POS);
    int filled =
        tank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 23500), false);
    helper.assertValueEqual(filled, 23500, "water accepted by the tank");

    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.containerMenu.setCarried(new ItemStack(Ic2Items.WATER_CELL));
    te.onNetworkEvent(player, 0);

    helper.assertValueEqual(
        tank.getFluidAmount(), 23500, "tank content after clicking with a cell that doesn't fit");
    ItemStack carried = player.containerMenu.getCarried();
    helper.assertTrue(
        carried.getItem() == Ic2Items.WATER_CELL && carried.getCount() == 1,
        "the water cell must not be consumed while less than 1000 mB fits, carried " + carried);

    // with exactly 1000 mB free the same click has to go through
    Ic2FluidStack drained = tank.drainMb(500, false);
    helper.assertTrue(
        drained != null && drained.getAmountMb() == 500,
        "tank should give up 500 mB, drained " + drained);
    te.onNetworkEvent(player, 0);

    helper.assertValueEqual(
        tank.getFluidAmount(), 24000, "tank content after clicking with a cell that fits exactly");
    helper.assertTrue(
        player.containerMenu.getCarried().isEmpty(),
        "the water cell should be consumed once its content fits");
    helper.assertValueEqual(
        countItems(player, Ic2Items.EMPTY_CELL), 1, "empty cells returned to the player");
    helper.succeed();
  }

  // tank GUI click, upstream 55aead36: shift-click batches the whole carried stack, a plain click
  // moves exactly one container, and no fluid is created or destroyed either way
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void tankGuiClickConservesFluidWithCells(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.TANK);
    TileEntityTank te = getTe(helper, MACHINE_POS, TileEntityTank.class);
    Ic2FluidTank tank = getTankBlockTank(helper, MACHINE_POS);
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);

    // shift-click (event 1) empties all three carried water cells into the tank
    player.containerMenu.setCarried(new ItemStack(Ic2Items.WATER_CELL, 3));
    te.onNetworkEvent(player, 1);
    assertTankContains(
        helper,
        tank,
        net.minecraft.world.level.material.Fluids.WATER,
        3000,
        "tank after batch drain");
    helper.assertValueEqual(
        tank.getFluidAmount(), 3000, "tank content after shift-clicking 3 water cells");
    helper.assertTrue(
        player.containerMenu.getCarried().isEmpty(), "the whole carried stack should be processed");
    helper.assertValueEqual(
        countItems(player, Ic2Items.EMPTY_CELL), 3, "empty cells returned to the player");

    // plain click (event 0) fills exactly one empty cell back from the tank
    player.containerMenu.setCarried(new ItemStack(Ic2Items.EMPTY_CELL));
    te.onNetworkEvent(player, 0);
    helper.assertValueEqual(tank.getFluidAmount(), 2000, "tank content after filling one cell");
    helper.assertValueEqual(
        countItems(player, Ic2Items.WATER_CELL), 1, "water cells returned to the player");
    helper.assertTrue(
        player.containerMenu.getCarried().isEmpty(), "the processed cell moves to the inventory");
    helper.succeed();
  }

  // empty cell fluid storage, upstream b18d8430: fluids without a dedicated cell item are kept on
  // the empty cell itself (custom data component) and given back in full on drain. Flowing water
  // stands in for a fluid that is not in the ItemClassicCell instances map; every still fluid in
  // the dev environment has its own cell item.
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void emptyCellStoresFluidWithoutDedicatedCellItem(GameTestHelper helper) {
    Fluid fluid = net.minecraft.world.level.material.Fluids.FLOWING_WATER;
    MutableObject<ItemStack> newStack = new MutableObject<>();

    // a fluid with a dedicated cell item still converts to that item
    int filled =
        FluidHandler.fillMb(
            new ItemStack(Ic2Items.EMPTY_CELL),
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000),
            false,
            newStack);
    helper.assertValueEqual(filled, 1000, "water accepted by an empty cell");
    helper.assertTrue(
        newStack.getValue().getItem() == Ic2Items.WATER_CELL,
        "water fills into a water cell, got " + newStack.getValue());

    // a fluid without one is stored on the empty cell item itself
    filled =
        FluidHandler.fillMb(
            new ItemStack(Ic2Items.EMPTY_CELL), Ic2FluidStack.create(fluid, 1000), false, newStack);
    helper.assertValueEqual(
        filled, 1000, "fluid without a dedicated cell accepted by an empty cell");
    ItemStack storedCell = newStack.getValue();
    helper.assertTrue(
        storedCell.getItem() == Ic2Items.EMPTY_CELL,
        "cell keeps the empty cell item id, got " + storedCell);
    Ic2FluidStack stored = Ic2FluidStack.get(storedCell);
    helper.assertTrue(
        stored != null && stored.getFluid() == fluid && stored.getAmountMb() == 1000,
        "cell should report the stored fluid, got " + stored);

    // double-filling is rejected, draining gives everything back and empties the cell
    filled = FluidHandler.fillMb(storedCell, Ic2FluidStack.create(fluid, 1000), false, null);
    helper.assertValueEqual(filled, 0, "fluid accepted by an already filled cell");
    Ic2FluidStack drained = FluidHandler.drainMb(storedCell, 1000, false, newStack);
    helper.assertTrue(
        drained != null && drained.getFluid() == fluid && drained.getAmountMb() == 1000,
        "cell should give the stored fluid back, got " + drained);
    Ic2FluidStack afterDrain = Ic2FluidStack.get(newStack.getValue());
    helper.assertTrue(
        afterDrain == null || afterDrain.isEmpty(),
        "drained cell should be empty again, has " + afterDrain);
    helper.succeed();
  }

  // tank GUI click + empty cell fluid storage: a fluid without a dedicated cell item survives a
  // full cell round trip through the tank slot without any loss
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void tankGuiClickRoundTripsFluidWithoutDedicatedCellItem(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.TANK);
    TileEntityTank te = getTe(helper, MACHINE_POS, TileEntityTank.class);
    Ic2FluidTank tank = getTankBlockTank(helper, MACHINE_POS);
    Fluid fluid = net.minecraft.world.level.material.Fluids.FLOWING_WATER;
    int filled = tank.fillMb(Ic2FluidStack.create(fluid, 5000), false);
    helper.assertValueEqual(filled, 5000, "fluid accepted by the tank");

    // fill an empty cell from the tank
    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.containerMenu.setCarried(new ItemStack(Ic2Items.EMPTY_CELL));
    te.onNetworkEvent(player, 0);
    helper.assertValueEqual(tank.getFluidAmount(), 4000, "tank content after filling a cell");
    ItemStack storedCell = firstItem(player, Ic2Items.EMPTY_CELL);
    Ic2FluidStack stored = storedCell != null ? Ic2FluidStack.get(storedCell) : null;
    helper.assertTrue(
        stored != null && stored.getFluid() == fluid && stored.getAmountMb() == 1000,
        "the filled cell should hold 1000 mB of the tank fluid, got " + stored);

    // pour it back
    player.getInventory().clearContent();
    player.containerMenu.setCarried(storedCell.copy());
    te.onNetworkEvent(player, 0);
    helper.assertValueEqual(
        tank.getFluidAmount(), 5000, "tank content after draining the cell back");
    ItemStack emptiedCell = firstItem(player, Ic2Items.EMPTY_CELL);
    Ic2FluidStack afterDrain = emptiedCell != null ? Ic2FluidStack.get(emptiedCell) : null;
    helper.assertTrue(
        emptiedCell != null && (afterDrain == null || afterDrain.isEmpty()),
        "the drained cell should be empty again, got " + emptiedCell + " with " + afterDrain);
    helper.succeed();
  }

  // fluid distributor, distribute mode (inactive): fluid is split evenly between all sides except
  // the facing
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void fluidDistributorSplitsEvenlyAcrossSides(GameTestHelper helper) {
    BlockPos eastPos = MACHINE_POS.east();
    BlockPos westPos = MACHINE_POS.west();
    helper.setBlock(
        MACHINE_POS,
        Ic2Blocks.FLUID_DISTRIBUTOR
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.UP));
    helper.setBlock(eastPos, Ic2Blocks.TANK);
    helper.setBlock(westPos, Ic2Blocks.TANK);
    TileEntityFluidDistributor te = getTe(helper, MACHINE_POS, TileEntityFluidDistributor.class);
    int filled =
        te.fluidTank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false);
    helper.assertValueEqual(filled, 1000, "water accepted by the fluid distributor");

    Ic2FluidTank eastTank = getTankBlockTank(helper, eastPos);
    Ic2FluidTank westTank = getTankBlockTank(helper, westPos);
    helper.succeedWhen(
        () -> {
          assertTankContains(
              helper,
              eastTank,
              net.minecraft.world.level.material.Fluids.WATER,
              500,
              "tank east of the distributor");
          assertTankContains(
              helper,
              westTank,
              net.minecraft.world.level.material.Fluids.WATER,
              500,
              "tank west of the distributor");
          helper.assertValueEqual(eastTank.getFluidAmount(), 500, "east tank share");
          helper.assertValueEqual(westTank.getFluidAmount(), 500, "west tank share");
          helper.assertTrue(te.fluidTank.isEmpty(), "fluid distributor should be drained");
        });
  }

  // fluid distributor, concentrate mode (active): all fluid goes to the facing side only
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void fluidDistributorConcentratesTowardFacing(GameTestHelper helper) {
    BlockPos eastPos = MACHINE_POS.east();
    BlockPos westPos = MACHINE_POS.west();
    helper.setBlock(
        MACHINE_POS,
        Ic2Blocks.FLUID_DISTRIBUTOR
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.EAST));
    helper.setBlock(eastPos, Ic2Blocks.TANK);
    helper.setBlock(westPos, Ic2Blocks.TANK);
    TileEntityFluidDistributor te = getTe(helper, MACHINE_POS, TileEntityFluidDistributor.class);
    te.setActive(true);
    int filled =
        te.fluidTank.fillMb(
            Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 1000), false);
    helper.assertValueEqual(filled, 1000, "water accepted by the fluid distributor");

    Ic2FluidTank eastTank = getTankBlockTank(helper, eastPos);
    Ic2FluidTank westTank = getTankBlockTank(helper, westPos);
    helper.succeedWhen(
        () -> {
          assertTankContains(
              helper,
              eastTank,
              net.minecraft.world.level.material.Fluids.WATER,
              1000,
              "tank on the distributor's facing side");
          helper.assertTrue(
              westTank.isEmpty(),
              "tank opposite the facing side must stay empty, has "
                  + westTank.getFluidAmount()
                  + " mB");
          helper.assertTrue(te.fluidTank.isEmpty(), "fluid distributor should be drained");
        });
  }

  // fluid regulator: pushes the configured amount (100 mB) into the facing tank once per second,
  // using 10 EU per operation, so 500 mB takes 5 operations and 50 EU.
  @GameTest(template = EMPTY, timeoutTicks = 300)
  public static void fluidRegulatorPushesConfiguredAmountPerSecond(GameTestHelper helper) {
    BlockPos tankPos = MACHINE_POS.east();
    helper.setBlock(
        MACHINE_POS,
        Ic2Blocks.FLUID_REGULATOR
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.EAST));
    helper.setBlock(tankPos, Ic2Blocks.TANK);
    TileEntityFluidRegulator te = getTe(helper, MACHINE_POS, TileEntityFluidRegulator.class);
    Energy energy = te.getComponent(Energy.class);
    energy.addEnergy(1000.0);
    te.onNetworkEvent(null, 100);
    helper.assertValueEqual(te.getOutputMb(), 100, "fluid regulator configured output");
    int filled =
        te.getFluidTank()
            .fillMb(
                Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 500), false);
    helper.assertValueEqual(filled, 500, "water accepted by the fluid regulator");

    Ic2FluidTank targetTank = getTankBlockTank(helper, tankPos);
    helper.succeedWhen(
        () -> {
          assertTankContains(
              helper,
              targetTank,
              net.minecraft.world.level.material.Fluids.WATER,
              500,
              "tank facing the fluid regulator");
          helper.assertTrue(te.getFluidTank().isEmpty(), "fluid regulator should be drained");
          Ic2GameTestAssertions.assertNear(
              helper,
              energy.getEnergy(),
              1000.0 - 50.0,
              "fluid regulator energy after 5 operations");
        });
  }

  // tank GUI drain, persistence: draining a tank into a cursor container through the GUI network
  // event must mark the tile changed. The hand-click path already calls setChanged; the GUI path
  // did
  // not, so the emptied tank was never persisted and reverted to full on chunk reload while the
  // player kept the filled cell (reload dupe). Covers the machine tank.
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void machineTankGuiDrainIsPersisted(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.TANK);
    Ic2FluidTank tank = firstTank(helper, MACHINE_POS);
    tank.fillMb(Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 5000), false);
    assertGuiDrainMarksTileChanged(helper, MACHINE_POS);
    helper.succeed();
  }

  // same persistence guarantee for the storage tank variant, which shares the LiquidUtil GUI path
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void storageTankGuiDrainIsPersisted(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.IRON_TANK);
    Ic2FluidTank tank = firstTank(helper, MACHINE_POS);
    tank.fillMb(
        Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 999000), false);
    assertGuiDrainMarksTileChanged(helper, MACHINE_POS);
    helper.succeed();
  }

  // and for the coke kiln grate, the third tile wired to the same GUI fluid transfer
  @GameTest(template = EMPTY, timeoutTicks = 100)
  public static void cokeKilnGrateGuiDrainIsPersisted(GameTestHelper helper) {
    helper.setBlock(MACHINE_POS, Ic2Blocks.COKE_KILN_GRATE);
    Ic2FluidTank tank = firstTank(helper, MACHINE_POS);
    tank.fillMb(Ic2FluidStack.create(net.minecraft.world.level.material.Fluids.WATER, 5000), false);
    assertGuiDrainMarksTileChanged(helper, MACHINE_POS);
    helper.succeed();
  }

  private static Ic2FluidTank firstTank(GameTestHelper helper, BlockPos pos) {
    return ((Ic2TileEntity) helper.getBlockEntity(pos))
        .getComponent(Fluids.class)
        .getAllTanks()
        .iterator()
        .next();
  }

  // clears the tile's chunk-unsaved flag, drains exactly one cell through the GUI network event,
  // and
  // asserts the fluid left the tank and the chunk was marked unsaved again. On a lone passive tile
  // nothing else dirties the chunk, so the flag flip is entirely down to the transfer's setChanged.
  private static void assertGuiDrainMarksTileChanged(GameTestHelper helper, BlockPos relativePos) {
    Ic2TileEntity te = (Ic2TileEntity) helper.getBlockEntity(relativePos);
    Ic2FluidTank tank = te.getComponent(Fluids.class).getAllTanks().iterator().next();
    int before = tank.getFluidAmount();
    helper.assertTrue(
        before >= 1000, "test setup: tank should hold at least 1000 mB, has " + before);

    Player player = helper.makeMockPlayer(GameType.SURVIVAL);
    player.containerMenu.setCarried(new ItemStack(Ic2Items.EMPTY_CELL));

    ServerLevel level = helper.getLevel();
    BlockPos abs = helper.absolutePos(relativePos);
    level.getChunkAt(abs).setUnsaved(false);
    ((INetworkClientTileEntityEventListener) te).onNetworkEvent(player, 0);

    helper.assertValueEqual(
        tank.getFluidAmount(), before - 1000, "one cell worth drained from the tank");
    helper.assertTrue(
        level.getChunkAt(abs).isUnsaved(),
        "the GUI fluid transfer must mark the tile changed so the drain is persisted");
  }

  private static void assertTankContains(
      GameTestHelper helper, Ic2FluidTank tank, Fluid fluid, int minAmountMb, String what) {
    Ic2FluidStack stack = tank.getFluidStack();
    helper.assertTrue(
        stack != null
            && !stack.isEmpty()
            && stack.getFluid() == fluid
            && stack.getAmountMb() >= minAmountMb,
        what
            + " should contain at least "
            + minAmountMb
            + " mB of "
            + fluid
            + ", has "
            + (stack == null ? "nothing" : stack.getAmountMb() + " mB of " + stack.getFluid()));
  }

  private static int countItems(Player player, Item item) {
    int count = 0;
    for (ItemStack stack : player.getInventory().items) {
      if (stack.getItem() == item) {
        count += stack.getCount();
      }
    }

    return count;
  }

  private static ItemStack firstItem(Player player, Item item) {
    for (ItemStack stack : player.getInventory().items) {
      if (stack.getItem() == item) {
        return stack;
      }
    }

    return null;
  }

  private static Ic2FluidTank getTankBlockTank(GameTestHelper helper, BlockPos pos) {
    BlockEntity be = helper.getBlockEntity(pos);
    if (!(be instanceof TileEntityTank tank)) {
      throw new IllegalStateException("expected a fluid tank at " + pos + ", found " + be);
    }

    return tank.getComponent(Fluids.class).getAllTanks().iterator().next();
  }

  private static <T extends BlockEntity> T getTe(
      GameTestHelper helper, BlockPos pos, Class<T> type) {
    BlockEntity be = helper.getBlockEntity(pos);
    if (!type.isInstance(be)) {
      throw new IllegalStateException(
          "expected " + type.getSimpleName() + " at " + pos + ", found " + be);
    }

    return type.cast(be);
  }
}
