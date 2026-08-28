package ic2.core.gametest;

import ic2.core.Ic2DamageSource;
import ic2.core.block.comp.Energy;
import ic2.core.block.machine.tileentity.TileEntityLiquidHeatExchanger;
import ic2.core.block.reactor.tileentity.TileEntityNuclearReactorElectric;
import ic2.core.block.reactor.tileentity.TileEntityRCI_LZH;
import ic2.core.block.reactor.tileentity.TileEntityRCI_RSH;
import ic2.core.block.reactor.tileentity.TileEntityReactorAccessHatch;
import ic2.core.block.reactor.tileentity.TileEntityReactorChamberElectric;
import ic2.core.block.reactor.tileentity.TileEntityReactorFluidPort;
import ic2.core.block.tileentity.Ic2TileEntityBlock;
import ic2.core.block.wiring.tileentity.TileEntityElectricCESU;
import ic2.core.block.wiring.tileentity.TileEntityElectricMFSU;
import ic2.core.fluid.Ic2FluidStack;
import ic2.core.item.armor.ItemArmorHazmat;
import ic2.core.item.reactor.AbstractDamageableReactorComponent;
import ic2.core.item.reactor.ItemReactorCondensator;
import ic2.core.item.reactor.ItemReactorHeatStorage;
import ic2.core.ref.Ic2Blocks;
import ic2.core.ref.Ic2Fluids;
import ic2.core.ref.Ic2Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("ic2")
@PrefixGameTestTemplate(false)
public class ReactorGameTests {
  private static final String EMPTY = "gametest/empty3x3x3";
  private static final String EMPTY_LARGE = "gametest/empty7x7x7";
  private static final BlockPos REACTOR_POS = new BlockPos(1, 1, 1);
  // center of the 7x7x7 template, so the 5x5x5 pressure vessel fits with one block of margin
  private static final BlockPos VESSEL_CENTER = new BlockPos(3, 3, 3);

  // the reactor only runs fuel while it has a redstone signal
  @GameTest(template = EMPTY)
  public static void reactorIdlesWithoutRedstoneSignal(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));

    // 60 ticks covers at least two reactor work ticks (one every 20)
    helper.runAtTickTime(
        60,
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper, reactor.getReactorEnergyOutput(), 0.0, "output without redstone");
          helper.assertValueEqual(reactor.getHeat(), 0, "hull heat without redstone");
          helper.succeed();
        });
  }

  // an isolated single uranium rod pulses itself once: 1 output unit = 5 EU/t, 4 hull heat per
  // second
  @GameTest(template = EMPTY)
  public static void singleUraniumRodOutputsOnePulse(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));

    helper.succeedWhen(
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper, reactor.getReactorEnergyOutput(), 1.0, "single rod output");
          Ic2GameTestAssertions.assertNear(
              helper, reactor.getOfferedEnergy(), 5.0, "single rod EU/t");
          helper.assertTrue(
              reactor.getHeat() >= 4 && reactor.getHeat() % 4 == 0,
              "uncooled single rod should add 4 hull heat per work tick, hull has "
                  + reactor.getHeat());
        });
  }

  // two adjacent rods pulse each other: 2 output units per rod instead of 1
  @GameTest(template = EMPTY)
  public static void adjacentRodsPulseEachOther(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));

    helper.succeedWhen(
        () ->
            Ic2GameTestAssertions.assertNear(
                helper, reactor.getReactorEnergyOutput(), 4.0, "output of two adjacent rods"));
  }

  // a neutron reflector bounces the rod's pulse back, doubling its output without extra fuel
  @GameTest(template = EMPTY)
  public static void neutronReflectorDoublesRodOutput(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.NEUTRON_REFLECTOR));

    helper.succeedWhen(
        () ->
            Ic2GameTestAssertions.assertNear(
                helper, reactor.getReactorEnergyOutput(), 2.0, "reflected rod output"));
  }

  // an adjacent coolant cell soaks up the rod's 4 heat per work tick, keeping the hull at 0
  @GameTest(template = EMPTY)
  public static void coolantCellAbsorbsRodHeat(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.REACTOR_COOLANT_CELL));

    ItemReactorHeatStorage cellItem = (ItemReactorHeatStorage) Ic2Items.REACTOR_COOLANT_CELL;

    helper.succeedWhen(
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper, reactor.getReactorEnergyOutput(), 1.0, "rod output next to coolant cell");
          int cellHeat = cellItem.getCurrentHeat(reactor.getItemAt(1, 0), reactor, 1, 0);
          helper.assertTrue(
              cellHeat >= 4 && cellHeat % 4 == 0,
              "coolant cell should hold the rod's heat, holds " + cellHeat);
          helper.assertValueEqual(reactor.getHeat(), 0, "hull heat with coolant cell");
        });
  }

  // a reactor heat vent pulls 5 heat per work tick out of the hull and vents it away
  @GameTest(template = EMPTY)
  public static void reactorHeatVentDrainsHullHeat(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.REACTOR_HEAT_VENT));
    reactor.setHeat(10);

    ItemReactorHeatStorage ventItem = (ItemReactorHeatStorage) Ic2Items.REACTOR_HEAT_VENT;

    helper.succeedWhen(
        () -> {
          helper.assertValueEqual(reactor.getHeat(), 0, "hull heat after venting");
          helper.assertValueEqual(
              ventItem.getCurrentHeat(reactor.getItemAt(0, 0), reactor, 0, 0),
              0,
              "vent's own stored heat");
        });
  }

  // each reactor plating adds 1000 heat capacity and dampens heat effects by 5%
  @GameTest(template = EMPTY)
  public static void platingRaisesHeatCapacity(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.REACTOR_PLATING));

    helper.succeedWhen(
        () -> {
          helper.assertValueEqual(
              reactor.getMaxHeat(), 11000, "hull heat capacity with one plating");
          Ic2GameTestAssertions.assertNear(
              helper,
              reactor.getHeatEffectModifier(),
              0.95,
              "heat effect modifier with one plating");
        });
  }

  // a rod on its last work tick turns into a depleted rod instead of vanishing
  @GameTest(template = EMPTY)
  public static void uraniumRodDepletesIntoDepletedRod(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);

    ItemStack rod = new ItemStack(Ic2Items.URANIUM_FUEL_ROD);
    // uranium rods last 20000 work ticks, fast-forward to the very last one
    ((AbstractDamageableReactorComponent) Ic2Items.URANIUM_FUEL_ROD).setUse(rod, 19999);
    reactor.reactorSlot.put(0, 0, rod);

    helper.succeedWhen(
        () -> {
          ItemStack stack = reactor.getItemAt(0, 0);
          helper.assertTrue(
              stack != null && stack.getItem() == Ic2Items.DEPLETED_URANIUM_FUEL_ROD,
              "spent rod should become a depleted uranium fuel rod, slot has " + stack);
        });
  }

  // items that aren't reactor components get thrown out of the grid
  @GameTest(template = EMPTY)
  public static void nonComponentItemsAreEjected(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    reactor.reactorSlot.put(0, 0, new ItemStack(Items.STICK));

    helper.succeedWhen(
        () -> {
          ItemStack stack = reactor.getItemAt(0, 0);
          helper.assertTrue(
              stack == null || stack.isEmpty(),
              "stick should have been ejected from the reactor, slot has " + stack);
          helper.assertItemEntityPresent(Items.STICK, REACTOR_POS, 2.0);
        });
  }

  // each attached reactor chamber adds a grid column and joins the reactor's energy net delegate
  @GameTest(template = EMPTY)
  public static void chambersExpandReactorGrid(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.west(), Ic2Blocks.REACTOR_CHAMBER);
    helper.setBlock(REACTOR_POS.east(), Ic2Blocks.REACTOR_CHAMBER);

    helper.succeedWhen(
        () -> {
          helper.assertValueEqual(reactor.getReactorSize(), 5, "grid columns with two chambers");
          helper.assertValueEqual(
              reactor.reactorSlot.size(), 30, "usable grid slots with two chambers");
          helper.assertValueEqual(
              reactor.getSubTiles().size(), 3, "energy net sub-tiles with two chambers");
        });
  }

  // A chamber shared by two reactors belongs to the first reactor in the fixed Direction order.
  // The other reactor must ignore it instead of destroying the chamber during redstone linking.
  @GameTest(template = EMPTY)
  public static void sharedChamberBindsToOneReactorWithoutBreaking(GameTestHelper helper) {
    TileEntityNuclearReactorElectric westReactor = placeReactor(helper, new BlockPos(0, 1, 1));
    TileEntityNuclearReactorElectric eastReactor = placeReactor(helper, new BlockPos(2, 1, 1));
    helper.setBlock(REACTOR_POS, Ic2Blocks.REACTOR_CHAMBER);
    TileEntityReactorChamberElectric chamber =
        getTe(helper, REACTOR_POS, TileEntityReactorChamberElectric.class);

    helper.succeedWhen(
        () -> {
          helper.assertBlockPresent(Ic2Blocks.REACTOR_CHAMBER, REACTOR_POS);
          helper.assertTrue(
              chamber.getReactorInstance() == westReactor,
              "shared chamber should bind to the west reactor first");
          helper.assertValueEqual(westReactor.getReactorSize(), 4, "west reactor chamber columns");
          helper.assertValueEqual(eastReactor.getReactorSize(), 3, "east reactor must ignore shared chamber");
        });
  }

  // the running reactor is an energy net source: an adjacent MFSU banks its 5 EU/t
  @GameTest(template = EMPTY)
  public static void reactorPowersAdjacentStorage(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    helper.setBlock(REACTOR_POS.below(), Ic2Blocks.MFSU);
    TileEntityElectricMFSU mfsu = getTe(helper, REACTOR_POS.below(), TileEntityElectricMFSU.class);

    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));

    helper.succeedWhen(
        () ->
            helper.assertTrue(
                mfsu.energy.getEnergy() >= 5.0,
                "MFSU should have banked reactor EU, has " + mfsu.energy.getEnergy()));
  }

  // a MOX rod scales its output with hull heat: at 50% heat each pulse is worth 4 * 0.5 + 1 = 3
  // output units
  @GameTest(template = EMPTY)
  public static void moxRodOutputScalesWithHullHeat(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.MOX_FUEL_ROD));
    reactor.setHeat(5000);

    helper.succeedWhen(
        () -> {
          // the work tick adds the rod's own heat before computing output, so compare against the
          // live hull heat
          float expected = 4.0F * ((float) reactor.getHeat() / reactor.getMaxHeat()) + 1.0F;
          helper.assertTrue(
              reactor.getReactorEnergyOutput() > 3.0F,
              "hot MOX rod should out-produce a cold rod, output is "
                  + reactor.getReactorEnergyOutput());
          Ic2GameTestAssertions.assertNear(
              helper,
              reactor.getReactorEnergyOutput(),
              expected,
              "MOX rod output at ~50% hull heat");
        });
  }

  // multi-cell rods pulse themselves: a dual rod yields 4 output units and 24 heat, a quad 12 units
  // and 96 heat
  @GameTest(template = EMPTY)
  public static void dualAndQuadRodsScaleOutputAndHeat(GameTestHelper helper) {
    TileEntityNuclearReactorElectric dual = placeReactor(helper, new BlockPos(0, 1, 1));
    TileEntityNuclearReactorElectric quad = placeReactor(helper, new BlockPos(2, 1, 1));
    helper.setBlock(new BlockPos(1, 1, 1), Blocks.REDSTONE_BLOCK);
    dual.reactorSlot.put(0, 0, new ItemStack(Ic2Items.DUAL_URANIUM_FUEL_ROD));
    quad.reactorSlot.put(0, 0, new ItemStack(Ic2Items.QUAD_URANIUM_FUEL_ROD));

    helper.succeedWhen(
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper, dual.getReactorEnergyOutput(), 4.0, "dual rod output");
          Ic2GameTestAssertions.assertNear(
              helper, quad.getReactorEnergyOutput(), 12.0, "quad rod output");
          helper.assertTrue(
              dual.getHeat() >= 24 && dual.getHeat() % 24 == 0,
              "dual rod should add 24 hull heat per work tick, hull has " + dual.getHeat());
          helper.assertTrue(
              quad.getHeat() >= 96 && quad.getHeat() % 96 == 0,
              "quad rod should add 96 hull heat per work tick, hull has " + quad.getHeat());
        });
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void oneQuadRodLayout(GameTestHelper helper) {
    verifyQuadRodLayout(helper, 1);
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void twoQuadRodLayout(GameTestHelper helper) {
    verifyQuadRodLayout(helper, 2);
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void threeQuadRodLayout(GameTestHelper helper) {
    verifyQuadRodLayout(helper, 3);
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void fourQuadRodLayout(GameTestHelper helper) {
    verifyQuadRodLayout(helper, 4);
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void fiveQuadRodLayout(GameTestHelper helper) {
    verifyQuadRodLayout(helper, 5);
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void sixQuadRodLayout(GameTestHelper helper) {
    verifyQuadRodLayout(helper, 6);
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void maximumOutputSixQuadRodLayout(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);
    for (Direction direction : Direction.values()) {
      helper.setBlock(VESSEL_CENTER.relative(direction), Ic2Blocks.REACTOR_CHAMBER);
    }
    helper.setBlock(VESSEL_CENTER.offset(-2, 0, 0), Blocks.REDSTONE_BLOCK);

    String[] layout = QuadRodReactorLayouts.maximumOutputSixQuadLayout();
    int quadRods = 0;
    int dualRods = 0;
    int singleRods = 0;
    int overclockedVents = 0;
    int componentVents = 0;
    for (int y = 0; y < layout.length; y++) {
      helper.assertValueEqual(layout[y].length(), 9, "mixed layout row width");
      for (int x = 0; x < layout[y].length(); x++) {
        switch (layout[y].charAt(x)) {
          case QuadRodReactorLayouts.QUAD_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.QUAD_URANIUM_FUEL_ROD));
            quadRods++;
          }
          case QuadRodReactorLayouts.DUAL_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.DUAL_URANIUM_FUEL_ROD));
            dualRods++;
          }
          case QuadRodReactorLayouts.SINGLE_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
            singleRods++;
          }
          case QuadRodReactorLayouts.OVERCLOCKED_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.OVERCLOCKED_HEAT_VENT));
            overclockedVents++;
          }
          case QuadRodReactorLayouts.COMPONENT_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.COMPONENT_HEAT_VENT));
            componentVents++;
          }
          case QuadRodReactorLayouts.EMPTY -> {
            // Deliberately empty.
          }
          default -> throw new IllegalArgumentException("unknown mixed reactor layout symbol");
        }
      }
    }

    helper.assertValueEqual(layout.length, 6, "mixed layout row count");
    helper.assertValueEqual(quadRods, 6, "quad rods in mixed layout");
    helper.assertValueEqual(dualRods, 2, "dual rods in mixed layout");
    helper.assertValueEqual(singleRods, 1, "single rods in mixed layout");
    helper.assertValueEqual(overclockedVents, 30, "overclocked vents in mixed layout");
    helper.assertValueEqual(componentVents, 12, "component vents in mixed layout");

    helper.runAtTickTime(
        220,
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper, reactor.getReactorEnergyOutput(), 84.0, "mixed six-quad output");

          int simulatedCycles = 0;
          while (!hasDepletedFuelRod(reactor) && simulatedCycles < 20000) {
            reactor.output = 0.0F;
            reactor.processChambers();
            simulatedCycles++;
          }

          helper.assertTrue(
              hasDepletedFuelRod(reactor),
              "a mixed-layout fuel rod should deplete within 20000 cycles");
          helper.assertValueEqual(reactor.getHeat(), 0, "mixed layout hull heat");

          int survivingOverclockedVents = 0;
          int survivingComponentVents = 0;
          ItemReactorHeatStorage overclockedVent =
              (ItemReactorHeatStorage) Ic2Items.OVERCLOCKED_HEAT_VENT;
          for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 9; x++) {
              ItemStack stack = reactor.getItemAt(x, y);
              if (stack != null && stack.getItem() == Ic2Items.OVERCLOCKED_HEAT_VENT) {
                survivingOverclockedVents++;
                helper.assertTrue(
                    overclockedVent.getCurrentHeat(stack, reactor, x, y)
                        < overclockedVent.getMaxHeat(stack, reactor, x, y),
                    "mixed-layout vent at " + x + "," + y + " should remain intact");
              } else if (stack != null && stack.getItem() == Ic2Items.COMPONENT_HEAT_VENT) {
                survivingComponentVents++;
              }
            }
          }
          helper.assertValueEqual(
              survivingOverclockedVents, 30, "intact mixed-layout overclocked vents");
          helper.assertValueEqual(
              survivingComponentVents, 12, "intact mixed-layout component vents");
          helper.succeed();
        });
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void clockedMixedMoxLayout(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);
    for (Direction direction : Direction.values()) {
      helper.setBlock(VESSEL_CENTER.relative(direction), Ic2Blocks.REACTOR_CHAMBER);
    }

    String[] layout = QuadRodReactorLayouts.clockedMixedMoxLayout();
    int dualMoxRods = 0;
    int singleMoxRods = 0;
    int overclockedVents = 0;
    int componentVents = 0;
    int reactorVents = 0;
    for (int y = 0; y < layout.length; y++) {
      helper.assertValueEqual(layout[y].length(), 9, "clocked MOX layout row width");
      for (int x = 0; x < layout[y].length(); x++) {
        switch (layout[y].charAt(x)) {
          case QuadRodReactorLayouts.DUAL_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.DUAL_MOX_FUEL_ROD));
            dualMoxRods++;
          }
          case QuadRodReactorLayouts.SINGLE_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.MOX_FUEL_ROD));
            singleMoxRods++;
          }
          case QuadRodReactorLayouts.OVERCLOCKED_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.OVERCLOCKED_HEAT_VENT));
            overclockedVents++;
          }
          case QuadRodReactorLayouts.COMPONENT_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.COMPONENT_HEAT_VENT));
            componentVents++;
          }
          case QuadRodReactorLayouts.REACTOR_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.REACTOR_HEAT_VENT));
            reactorVents++;
          }
          default -> throw new IllegalArgumentException("unknown clocked MOX layout symbol");
        }
      }
    }

    helper.assertValueEqual(layout.length, 6, "clocked MOX layout row count");
    helper.assertValueEqual(dualMoxRods, 4, "dual MOX rods in clocked layout");
    helper.assertValueEqual(singleMoxRods, 2, "single MOX rods in clocked layout");
    helper.assertValueEqual(overclockedVents, 8, "overclocked vents in clocked layout");
    helper.assertValueEqual(componentVents, 19, "component vents in clocked layout");
    helper.assertValueEqual(reactorVents, 21, "reactor vents in clocked layout");

    boolean[] powered = {false};
    reactor.redstone.addRedstoneModifier(input -> powered[0] ? 15 : 0);

    helper.runAtTickTime(
        20,
        () -> {
          int totalCycles = 0;
          int poweredCycles = 0;
          int peakHullHeat = reactor.getHeat();
          double totalOutput = 0.0;

          // Cold-start preheat: 48 continuously powered cycles raise this exact layout to 1488
          // hull heat. The regular clock can then take over without external heat injection.
          powered[0] = true;
          reactor.redstone.update();
          for (int cycle = 0; cycle < 48; cycle++) {
            reactor.output = 0.0F;
            reactor.processChambers();
            totalOutput += reactor.getReactorEnergyOutput();
            poweredCycles++;
            totalCycles++;
            peakHullHeat = Math.max(peakHullHeat, reactor.getHeat());
          }
          helper.assertValueEqual(reactor.getHeat(), 1488, "clocked MOX preheat hull heat");

          int clockCycle = 0;
          while (!hasDepletedMoxFuelRod(reactor) && poweredCycles < 10000) {
            powered[0] = clockCycle % 14 < 13;
            reactor.redstone.update();
            reactor.output = 0.0F;
            reactor.processChambers();
            if (powered[0]) {
              poweredCycles++;
            }
            totalOutput += reactor.getReactorEnergyOutput();
            peakHullHeat = Math.max(peakHullHeat, reactor.getHeat());
            helper.assertTrue(
                reactor.getHeat() < reactor.getMaxHeat(),
                "clocked MOX hull heat must stay below its capacity");
            totalCycles++;
            clockCycle++;
          }

          helper.assertTrue(hasDepletedMoxFuelRod(reactor), "a clocked MOX rod should deplete");
          helper.assertValueEqual(poweredCycles, 10000, "powered MOX cycles to depletion");
          helper.assertValueEqual(totalCycles, 10765, "total cycles to MOX depletion");
          helper.assertValueEqual(peakHullHeat, 9531, "clocked MOX peak hull heat");
          helper.assertTrue(
              totalOutput * 5.0 / totalCycles > 600.0,
              "clocked MOX average output should exceed 600 EU/t");

          int survivingOverclockedVents = 0;
          int survivingComponentVents = 0;
          int survivingReactorVents = 0;
          for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 9; x++) {
              ItemStack stack = reactor.getItemAt(x, y);
              if (stack != null && stack.getItem() == Ic2Items.OVERCLOCKED_HEAT_VENT) {
                survivingOverclockedVents++;
              } else if (stack != null && stack.getItem() == Ic2Items.COMPONENT_HEAT_VENT) {
                survivingComponentVents++;
              } else if (stack != null && stack.getItem() == Ic2Items.REACTOR_HEAT_VENT) {
                survivingReactorVents++;
              }
            }
          }
          helper.assertValueEqual(survivingOverclockedVents, 8, "intact clocked overclocked vents");
          helper.assertValueEqual(survivingComponentVents, 19, "intact clocked component vents");
          helper.assertValueEqual(survivingReactorVents, 21, "intact clocked reactor vents");
          helper.succeed();
        });
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void clockedHighOutputMoxLayout(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);
    for (Direction direction : Direction.values()) {
      helper.setBlock(VESSEL_CENTER.relative(direction), Ic2Blocks.REACTOR_CHAMBER);
    }

    String[] layout = QuadRodReactorLayouts.clockedHighOutputMoxLayout();
    int dualMoxRods = 0;
    int singleMoxRods = 0;
    int overclockedVents = 0;
    int componentVents = 0;
    int reactorVents = 0;
    for (int y = 0; y < layout.length; y++) {
      helper.assertValueEqual(layout[y].length(), 9, "high-output MOX layout row width");
      for (int x = 0; x < layout[y].length(); x++) {
        switch (layout[y].charAt(x)) {
          case QuadRodReactorLayouts.DUAL_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.DUAL_MOX_FUEL_ROD));
            dualMoxRods++;
          }
          case QuadRodReactorLayouts.SINGLE_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.MOX_FUEL_ROD));
            singleMoxRods++;
          }
          case QuadRodReactorLayouts.OVERCLOCKED_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.OVERCLOCKED_HEAT_VENT));
            overclockedVents++;
          }
          case QuadRodReactorLayouts.COMPONENT_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.COMPONENT_HEAT_VENT));
            componentVents++;
          }
          case QuadRodReactorLayouts.REACTOR_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.REACTOR_HEAT_VENT));
            reactorVents++;
          }
          default -> throw new IllegalArgumentException("unknown high-output MOX layout symbol");
        }
      }
    }

    helper.assertValueEqual(layout.length, 6, "high-output MOX layout row count");
    helper.assertValueEqual(dualMoxRods, 5, "dual MOX rods in high-output layout");
    helper.assertValueEqual(singleMoxRods, 1, "single MOX rods in high-output layout");
    helper.assertValueEqual(overclockedVents, 9, "overclocked vents in high-output layout");
    helper.assertValueEqual(componentVents, 27, "component vents in high-output layout");
    helper.assertValueEqual(reactorVents, 12, "reactor vents in high-output layout");

    boolean[] powered = {false};
    reactor.redstone.addRedstoneModifier(input -> powered[0] ? 15 : 0);

    helper.runAtTickTime(
        20,
        () -> {
          int totalCycles = 0;
          int poweredCycles = 0;
          int peakHullHeat = reactor.getHeat();
          double totalOutput = 0.0;

          powered[0] = true;
          reactor.redstone.update();
          for (int cycle = 0; cycle < 85; cycle++) {
            reactor.output = 0.0F;
            reactor.processChambers();
            totalOutput += reactor.getReactorEnergyOutput();
            poweredCycles++;
            totalCycles++;
            peakHullHeat = Math.max(peakHullHeat, reactor.getHeat());
          }
          helper.assertValueEqual(reactor.getHeat(), 8160, "high-output MOX preheat hull heat");

          int clockCycle = 0;
          while (!hasDepletedMoxFuelRod(reactor) && poweredCycles < 10000) {
            powered[0] = clockCycle % 5 < 4;
            reactor.redstone.update();
            reactor.output = 0.0F;
            reactor.processChambers();
            if (powered[0]) {
              poweredCycles++;
            }
            totalOutput += reactor.getReactorEnergyOutput();
            peakHullHeat = Math.max(peakHullHeat, reactor.getHeat());
            helper.assertTrue(
                reactor.getHeat() < reactor.getMaxHeat(),
                "high-output MOX hull heat must stay below its capacity");
            totalCycles++;
            clockCycle++;
          }

          double averageEuPerTick = totalOutput * 5.0 / totalCycles;
          helper.assertTrue(hasDepletedMoxFuelRod(reactor), "a high-output MOX rod should deplete");
          helper.assertValueEqual(poweredCycles, 10000, "high-output powered cycles to depletion");
          helper.assertValueEqual(totalCycles, 12478, "high-output total cycles to depletion");
          helper.assertValueEqual(peakHullHeat, 8544, "high-output MOX peak hull heat");
          helper.assertTrue(
              averageEuPerTick > 795.0 && averageEuPerTick < 805.0,
              "high-output MOX average should be approximately 800 EU/t, was " + averageEuPerTick);

          int survivingOverclockedVents = 0;
          int survivingComponentVents = 0;
          int survivingReactorVents = 0;
          for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 9; x++) {
              ItemStack stack = reactor.getItemAt(x, y);
              if (stack != null && stack.getItem() == Ic2Items.OVERCLOCKED_HEAT_VENT) {
                survivingOverclockedVents++;
              } else if (stack != null && stack.getItem() == Ic2Items.COMPONENT_HEAT_VENT) {
                survivingComponentVents++;
              } else if (stack != null && stack.getItem() == Ic2Items.REACTOR_HEAT_VENT) {
                survivingReactorVents++;
              }
            }
          }
          helper.assertValueEqual(
              survivingOverclockedVents, 9, "intact high-output overclocked vents");
          helper.assertValueEqual(
              survivingComponentVents, 27, "intact high-output component vents");
          helper.assertValueEqual(survivingReactorVents, 12, "intact high-output reactor vents");
          helper.succeed();
        });
  }

  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void clocked1020EuMoxLayout(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);
    for (Direction direction : Direction.values()) {
      helper.setBlock(VESSEL_CENTER.relative(direction), Ic2Blocks.REACTOR_CHAMBER);
    }

    String[] layout = QuadRodReactorLayouts.clocked1020EuMoxLayout();
    int quadMoxRods = 0;
    int dualMoxRods = 0;
    int singleMoxRods = 0;
    int overclockedVents = 0;
    int componentVents = 0;
    int reactorVents = 0;
    for (int y = 0; y < layout.length; y++) {
      helper.assertValueEqual(layout[y].length(), 9, "1020 EU/t MOX layout row width");
      for (int x = 0; x < layout[y].length(); x++) {
        switch (layout[y].charAt(x)) {
          case QuadRodReactorLayouts.QUAD_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.QUAD_MOX_FUEL_ROD));
            quadMoxRods++;
          }
          case QuadRodReactorLayouts.DUAL_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.DUAL_MOX_FUEL_ROD));
            dualMoxRods++;
          }
          case QuadRodReactorLayouts.SINGLE_MOX_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.MOX_FUEL_ROD));
            singleMoxRods++;
          }
          case QuadRodReactorLayouts.OVERCLOCKED_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.OVERCLOCKED_HEAT_VENT));
            overclockedVents++;
          }
          case QuadRodReactorLayouts.COMPONENT_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.COMPONENT_HEAT_VENT));
            componentVents++;
          }
          case QuadRodReactorLayouts.REACTOR_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.REACTOR_HEAT_VENT));
            reactorVents++;
          }
          default -> throw new IllegalArgumentException("unknown 1020 EU/t MOX layout symbol");
        }
      }
    }

    helper.assertValueEqual(layout.length, 6, "1020 EU/t MOX layout row count");
    helper.assertValueEqual(quadMoxRods, 2, "quad MOX rods in 1020 EU/t layout");
    helper.assertValueEqual(dualMoxRods, 2, "dual MOX rods in 1020 EU/t layout");
    helper.assertValueEqual(singleMoxRods, 1, "single MOX rods in 1020 EU/t layout");
    helper.assertValueEqual(overclockedVents, 12, "overclocked vents in 1020 EU/t layout");
    helper.assertValueEqual(componentVents, 25, "component vents in 1020 EU/t layout");
    helper.assertValueEqual(reactorVents, 12, "reactor vents in 1020 EU/t layout");

    boolean[] powered = {false};
    reactor.redstone.addRedstoneModifier(input -> powered[0] ? 15 : 0);

    helper.runAtTickTime(
        20,
        () -> {
          int totalCycles = 0;
          int poweredCycles = 0;
          int peakHullHeat = reactor.getHeat();
          double totalOutput = 0.0;

          powered[0] = true;
          reactor.redstone.update();
          for (int cycle = 0; cycle < 645; cycle++) {
            reactor.output = 0.0F;
            reactor.processChambers();
            totalOutput += reactor.getReactorEnergyOutput();
            poweredCycles++;
            totalCycles++;
            peakHullHeat = Math.max(peakHullHeat, reactor.getHeat());
          }
          helper.assertValueEqual(reactor.getHeat(), 7740, "1020 EU/t MOX preheat hull heat");

          int clockCycle = 0;
          while (!hasDepletedMoxFuelRod(reactor) && poweredCycles < 10000) {
            powered[0] = clockCycle % 42 < 41;
            reactor.redstone.update();
            reactor.output = 0.0F;
            reactor.processChambers();
            if (powered[0]) {
              poweredCycles++;
            }
            totalOutput += reactor.getReactorEnergyOutput();
            peakHullHeat = Math.max(peakHullHeat, reactor.getHeat());
            helper.assertTrue(
                reactor.getHeat() < reactor.getMaxHeat(),
                "1020 EU/t MOX hull heat must stay below its capacity");
            totalCycles++;
            clockCycle++;
          }

          double averageEuPerTick = totalOutput * 5.0 / totalCycles;
          helper.assertTrue(hasDepletedMoxFuelRod(reactor), "a 1020 EU/t MOX rod should deplete");
          helper.assertValueEqual(poweredCycles, 10000, "1020 EU/t powered cycles to depletion");
          helper.assertValueEqual(totalCycles, 10228, "1020 EU/t total cycles to depletion");
          helper.assertValueEqual(peakHullHeat, 8232, "1020 EU/t MOX peak hull heat");
          helper.assertTrue(
              averageEuPerTick > 1019.0 && averageEuPerTick < 1021.0,
              "MOX average should be approximately 1020 EU/t, was " + averageEuPerTick);

          int survivingOverclockedVents = 0;
          int survivingComponentVents = 0;
          int survivingReactorVents = 0;
          for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 9; x++) {
              ItemStack stack = reactor.getItemAt(x, y);
              if (stack != null && stack.getItem() == Ic2Items.OVERCLOCKED_HEAT_VENT) {
                survivingOverclockedVents++;
              } else if (stack != null && stack.getItem() == Ic2Items.COMPONENT_HEAT_VENT) {
                survivingComponentVents++;
              } else if (stack != null && stack.getItem() == Ic2Items.REACTOR_HEAT_VENT) {
                survivingReactorVents++;
              }
            }
          }
          helper.assertValueEqual(
              survivingOverclockedVents, 12, "intact 1020 EU/t overclocked vents");
          helper.assertValueEqual(survivingComponentVents, 25, "intact 1020 EU/t component vents");
          helper.assertValueEqual(survivingReactorVents, 12, "intact 1020 EU/t reactor vents");
          helper.succeed();
        });
  }

  // a lithium rod next to a pulsing rod breeds on a hot hull, turning into a tritium rod once full
  @GameTest(template = EMPTY)
  public static void lithiumRodBreedsIntoTritium(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);

    ItemStack lithium = new ItemStack(Ic2Items.LITHIUM_FUEL_ROD);
    // lithium needs 10000 breeding levels; at 3000 hull heat it gains heat/3000 = 1 per pulse, so
    // start one short
    ((AbstractDamageableReactorComponent) Ic2Items.LITHIUM_FUEL_ROD).setUse(lithium, 9999);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, lithium);
    reactor.setHeat(3000);

    helper.succeedWhen(
        () -> {
          ItemStack stack = reactor.getItemAt(1, 0);
          helper.assertTrue(
              stack != null && stack.getItem() == Ic2Items.TRITIUM_FUEL_ROD,
              "fully bred lithium rod should become a tritium fuel rod, slot has " + stack);
        });
  }

  // the iridium reflector doubles rod output like a regular reflector but takes no wear
  @GameTest(template = EMPTY)
  public static void iridiumReflectorBouncesWithoutWear(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.IRIDIUM_NEUTRON_REFLECTOR));

    helper.succeedWhen(
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper, reactor.getReactorEnergyOutput(), 2.0, "rod output with iridium reflector");
          ItemStack stack = reactor.getItemAt(1, 0);
          helper.assertTrue(
              stack != null && stack.getItem() == Ic2Items.IRIDIUM_NEUTRON_REFLECTOR,
              "iridium reflector should survive reflecting, slot has " + stack);
        });
  }

  // a neutron reflector on its last pulse is destroyed, dropping the rod back to single output
  @GameTest(template = EMPTY)
  public static void wornNeutronReflectorBreaks(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);

    ItemStack reflector = new ItemStack(Ic2Items.NEUTRON_REFLECTOR);
    // neutron reflectors absorb 30000 pulses, fast-forward to the very last one
    ((AbstractDamageableReactorComponent) Ic2Items.NEUTRON_REFLECTOR).setUse(reflector, 29999);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, reflector);

    helper.succeedWhen(
        () -> {
          ItemStack stack = reactor.getItemAt(1, 0);
          helper.assertTrue(
              stack == null || stack.isEmpty(),
              "worn-out reflector should be destroyed, slot has " + stack);
          Ic2GameTestAssertions.assertNear(
              helper,
              reactor.getReactorEnergyOutput(),
              1.0,
              "rod output after losing its reflector");
        });
  }

  // a coolant cell pushed past its 10000 heat capacity shatters instead of storing the overflow
  @GameTest(template = EMPTY)
  public static void overfilledCoolantCellShatters(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);

    ItemStack cell = new ItemStack(Ic2Items.REACTOR_COOLANT_CELL);
    // the rod's next 4 heat push the cell past its 10000 capacity
    ((AbstractDamageableReactorComponent) Ic2Items.REACTOR_COOLANT_CELL).setUse(cell, 9998);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, cell);

    helper.succeedWhen(
        () -> {
          ItemStack stack = reactor.getItemAt(1, 0);
          helper.assertTrue(
              stack == null || stack.isEmpty(),
              "overfilled coolant cell should shatter, slot has " + stack);
        });
  }

  // condensators soak up rod heat like coolant cells, keeping the hull cold
  @GameTest(template = EMPTY)
  public static void condensatorAbsorbsRodHeat(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    helper.setBlock(REACTOR_POS.above(), Blocks.REDSTONE_BLOCK);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.LZH_CONDENSATOR));

    ItemReactorCondensator condensator = (ItemReactorCondensator) Ic2Items.LZH_CONDENSATOR;

    helper.succeedWhen(
        () -> {
          helper.assertTrue(
              condensator.getUseFraction(reactor.getItemAt(1, 0)) > 0.0,
              "condensator should hold the rod's heat");
          helper.assertValueEqual(reactor.getHeat(), 0, "hull heat with condensator");
        });
  }

  // the RSH/LZH coolant interfaces recharge nearly-full condensators in the reactor for a resource
  // block and 1000 EU
  @GameTest(template = EMPTY)
  public static void rciRechargesCondensators(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    // each RCI services the reactor behind its back face
    helper.setBlock(
        new BlockPos(2, 1, 1),
        Ic2Blocks.RCI_RSH
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.EAST));
    helper.setBlock(
        new BlockPos(0, 1, 1),
        Ic2Blocks.RCI_LZH
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.WEST));
    TileEntityRCI_RSH rsh = getTe(helper, new BlockPos(2, 1, 1), TileEntityRCI_RSH.class);
    TileEntityRCI_LZH lzh = getTe(helper, new BlockPos(0, 1, 1), TileEntityRCI_LZH.class);

    // both condensators start at 90% heat, above the 85% recharge threshold
    ItemStack rshCondensator = new ItemStack(Ic2Items.RSH_CONDENSATOR);
    ((ItemReactorCondensator) Ic2Items.RSH_CONDENSATOR).setUse(rshCondensator, 18000);
    ItemStack lzhCondensator = new ItemStack(Ic2Items.LZH_CONDENSATOR);
    ((ItemReactorCondensator) Ic2Items.LZH_CONDENSATOR).setUse(lzhCondensator, 90000);
    reactor.reactorSlot.put(0, 0, rshCondensator);
    reactor.reactorSlot.put(1, 0, lzhCondensator);

    rsh.getComponent(Energy.class).addEnergy(2000.0);
    lzh.getComponent(Energy.class).addEnergy(2000.0);
    rsh.inputSlot.put(0, new ItemStack(Blocks.REDSTONE_BLOCK));
    lzh.inputSlot.put(0, new ItemStack(Blocks.LAPIS_BLOCK));

    helper.succeedWhen(
        () -> {
          Ic2GameTestAssertions.assertNear(
              helper,
              ((ItemReactorCondensator) Ic2Items.RSH_CONDENSATOR)
                  .getUseFraction(reactor.getItemAt(0, 0)),
              0.0,
              "RSH condensator heat after recharge");
          Ic2GameTestAssertions.assertNear(
              helper,
              ((ItemReactorCondensator) Ic2Items.LZH_CONDENSATOR)
                  .getUseFraction(reactor.getItemAt(1, 0)),
              0.0,
              "LZH condensator heat after recharge");
          helper.assertTrue(
              rsh.inputSlot.isEmpty(), "RSH interface should consume its redstone block");
          helper.assertTrue(
              lzh.inputSlot.isEmpty(), "LZH interface should consume its lapis block");
        });
  }

  // a reactor heat exchanger pulls up to 72 heat per work tick from the hull into itself
  @GameTest(template = EMPTY)
  public static void reactorHeatExchangerDrainsHull(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.REACTOR_HEAT_EXCHANGER));
    reactor.setHeat(2000);

    ItemReactorHeatStorage exchanger = (ItemReactorHeatStorage) Ic2Items.REACTOR_HEAT_EXCHANGER;

    helper.succeedWhen(
        () -> {
          int held = exchanger.getCurrentHeat(reactor.getItemAt(0, 0), reactor, 0, 0);
          helper.assertTrue(
              held >= 72 && held % 72 == 0,
              "exchanger should pull 72 hull heat per work tick, holds " + held);
          helper.assertValueEqual(
              reactor.getHeat(), 2000 - held, "hull heat after exchanger transfer");
        });
  }

  // a component heat exchanger levels heat between itself and its neighbors, up to 36 per work tick
  @GameTest(template = EMPTY)
  public static void componentHeatExchangerBalancesNeighbors(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    ItemStack cell = new ItemStack(Ic2Items.REACTOR_COOLANT_CELL);
    ((AbstractDamageableReactorComponent) Ic2Items.REACTOR_COOLANT_CELL).setUse(cell, 5000);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.COMPONENT_HEAT_EXCHANGER));
    reactor.reactorSlot.put(1, 0, cell);

    ItemReactorHeatStorage exchanger = (ItemReactorHeatStorage) Ic2Items.COMPONENT_HEAT_EXCHANGER;
    ItemReactorHeatStorage cellItem = (ItemReactorHeatStorage) Ic2Items.REACTOR_COOLANT_CELL;

    helper.succeedWhen(
        () -> {
          int held = exchanger.getCurrentHeat(reactor.getItemAt(0, 0), reactor, 0, 0);
          int cellHeat = cellItem.getCurrentHeat(reactor.getItemAt(1, 0), reactor, 1, 0);
          helper.assertTrue(held > 0, "exchanger should take heat from the hot coolant cell");
          helper.assertValueEqual(held + cellHeat, 5000, "total heat during balancing");
          helper.assertValueEqual(reactor.getHeat(), 0, "hull heat during component balancing");
        });
  }

  // a component heat vent strips 4 heat per work tick from each neighboring component
  @GameTest(template = EMPTY)
  public static void componentHeatVentCoolsNeighbors(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    ItemReactorHeatStorage cellItem = (ItemReactorHeatStorage) Ic2Items.REACTOR_COOLANT_CELL;

    ItemStack west = new ItemStack(Ic2Items.REACTOR_COOLANT_CELL);
    ItemStack east = new ItemStack(Ic2Items.REACTOR_COOLANT_CELL);
    ((AbstractDamageableReactorComponent) Ic2Items.REACTOR_COOLANT_CELL).setUse(west, 100);
    ((AbstractDamageableReactorComponent) Ic2Items.REACTOR_COOLANT_CELL).setUse(east, 100);
    reactor.reactorSlot.put(0, 0, west);
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.COMPONENT_HEAT_VENT));
    reactor.reactorSlot.put(2, 0, east);

    helper.succeedWhen(
        () -> {
          int westHeat = cellItem.getCurrentHeat(reactor.getItemAt(0, 0), reactor, 0, 0);
          int eastHeat = cellItem.getCurrentHeat(reactor.getItemAt(2, 0), reactor, 2, 0);
          helper.assertValueEqual(
              westHeat, eastHeat, "both neighbors should cool at the same rate");
          helper.assertTrue(
              westHeat < 100 && (100 - westHeat) % 4 == 0,
              "spread vent should cool 4 heat per work tick, cell holds " + westHeat);
        });
  }

  // an overclocked heat vent pulls 36 hull heat per work tick but only vents 20, banking the
  // difference
  @GameTest(template = EMPTY)
  public static void overclockedVentTradesStorageForHullCooling(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.OVERCLOCKED_HEAT_VENT));
    reactor.setHeat(720);

    ItemReactorHeatStorage vent = (ItemReactorHeatStorage) Ic2Items.OVERCLOCKED_HEAT_VENT;

    helper.succeedWhen(
        () -> {
          int drained = 720 - reactor.getHeat();
          int held = vent.getCurrentHeat(reactor.getItemAt(0, 0), reactor, 0, 0);
          helper.assertTrue(
              drained >= 36 && drained % 36 == 0,
              "vent should drain 36 hull heat per work tick, drained " + drained);
          helper.assertValueEqual(
              held, drained / 36 * 16, "vent should bank 16 of every 36 drained heat");
        });
  }

  // a full 5x5x5 pressure vessel flips the reactor to fluid mode: off the energy net, boiling
  // coolant into hot coolant
  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void pressureVesselBoilsCoolantIntoHotCoolant(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = buildFluidReactor(helper);
    helper.setBlock(VESSEL_CENTER.offset(-2, 0, 0), Ic2Blocks.REACTOR_REDSTONE_PORT);
    helper.setBlock(VESSEL_CENTER.offset(-3, 0, 0), Blocks.REDSTONE_BLOCK);

    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.REACTOR_HEAT_VENT));
    int filled =
        reactor.inputTank.fillMb(Ic2FluidStack.create(Ic2Fluids.COOLANT.still(), 1000), false);
    helper.assertValueEqual(filled, 1000, "coolant accepted by the reactor input tank");

    helper.succeedWhen(
        () -> {
          helper.assertTrue(reactor.isFluidCooled(), "reactor should detect the pressure vessel");
          helper.assertTrue(
              !reactor.addedToEnergyNet, "fluid-cooled reactor should leave the energy net");
          Ic2GameTestAssertions.assertNear(
              helper,
              reactor.getReactorEnergyOutput(),
              1.0,
              "rod output via the redstone port signal");
          helper.assertTrue(
              reactor.outputTank.hasExactFluid(Ic2Fluids.HOT_COOLANT.still())
                  && reactor.outputTank.getFluidAmount() > 0,
              "output tank should hold hot coolant, has "
                  + reactor.outputTank.getFluidAmount()
                  + " mB");
          helper.assertTrue(
              reactor.inputTank.getFluidAmount() < 1000, "input tank coolant should be consumed");
        });
  }

  // the redstone port relays an external signal through the vessel wall to the sealed reactor
  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void redstonePortControlsSealedReactor(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = buildFluidReactor(helper);
    helper.setBlock(VESSEL_CENTER.offset(-2, 0, 0), Ic2Blocks.REACTOR_REDSTONE_PORT);
    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));

    helper.runAtTickTime(
        60,
        () -> {
          helper.assertTrue(reactor.isFluidCooled(), "reactor should detect the pressure vessel");
          Ic2GameTestAssertions.assertNear(
              helper,
              reactor.getReactorEnergyOutput(),
              0.0,
              "output with an unpowered redstone port");
          helper.setBlock(VESSEL_CENTER.offset(-3, 0, 0), Blocks.REDSTONE_BLOCK);
        });

    helper.succeedWhen(
        () ->
            Ic2GameTestAssertions.assertNear(
                helper,
                reactor.getReactorEnergyOutput(),
                1.0,
                "output once the redstone port is powered"));
  }

  // the access hatch exposes the sealed reactor's grid as a container through the vessel wall
  @GameTest(template = EMPTY_LARGE, timeoutTicks = 200)
  public static void accessHatchExposesSealedReactorGrid(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = buildFluidReactor(helper);
    helper.setBlock(VESSEL_CENTER.offset(0, 2, 0), Ic2Blocks.REACTOR_ACCESS_HATCH);
    TileEntityReactorAccessHatch hatch =
        getTe(helper, VESSEL_CENTER.offset(0, 2, 0), TileEntityReactorAccessHatch.class);

    helper.succeedWhen(
        () -> {
          helper.assertTrue(
              hatch.getReactorInstance() == reactor, "hatch should find the sealed reactor");
          helper.assertValueEqual(
              hatch.getContainerSize(), reactor.getContainerSize(), "hatch container size");
          hatch.setItem(0, new ItemStack(Ic2Items.REACTOR_COOLANT_CELL));
          ItemStack stack = reactor.getItemAt(0, 0);
          helper.assertTrue(
              stack != null && stack.getItem() == Ic2Items.REACTOR_COOLANT_CELL,
              "cell inserted through the hatch should land in grid slot (0,0), slot has " + stack);
        });
  }

  // full hot-coolant loop: fluid port ejects into a liquid heat exchanger whose heat drives a
  // stirling generator
  @GameTest(template = EMPTY_LARGE, timeoutTicks = 600)
  public static void hotCoolantLoopDrivesLiquidHeatExchanger(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = buildFluidReactor(helper);
    helper.setBlock(VESSEL_CENTER.offset(-2, 0, 0), Ic2Blocks.REACTOR_REDSTONE_PORT);
    helper.setBlock(VESSEL_CENTER.offset(-3, 0, 0), Blocks.REDSTONE_BLOCK);
    helper.setBlock(VESSEL_CENTER.offset(2, 0, 0), Ic2Blocks.REACTOR_FLUID_PORT);
    helper.setBlock(
        VESSEL_CENTER.offset(3, 0, 0),
        Ic2Blocks.LIQUID_HEAT_EXCHANGER
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.NORTH));
    helper.setBlock(
        VESSEL_CENTER.offset(3, 0, -1),
        Ic2Blocks.STIRLING_GENERATOR
            .defaultBlockState()
            .setValue(Ic2TileEntityBlock.anyFacingProperty, Direction.SOUTH));
    helper.setBlock(VESSEL_CENTER.offset(3, 0, -2), Ic2Blocks.CESU);

    TileEntityReactorFluidPort port =
        getTe(helper, VESSEL_CENTER.offset(2, 0, 0), TileEntityReactorFluidPort.class);
    TileEntityLiquidHeatExchanger exchanger =
        getTe(helper, VESSEL_CENTER.offset(3, 0, 0), TileEntityLiquidHeatExchanger.class);
    TileEntityElectricCESU cesu =
        getTe(helper, VESSEL_CENTER.offset(3, 0, -2), TileEntityElectricCESU.class);

    port.upgradeSlot.put(0, new ItemStack(Ic2Items.FLUID_EJECTOR_UPGRADE));
    for (int i = 0; i < exchanger.heatexchangerslots.size(); i++) {
      exchanger.heatexchangerslots.put(i, new ItemStack(Ic2Items.HEAT_CONDUCTOR));
    }

    reactor.reactorSlot.put(0, 0, new ItemStack(Ic2Items.URANIUM_FUEL_ROD));
    reactor.reactorSlot.put(1, 0, new ItemStack(Ic2Items.REACTOR_HEAT_VENT));
    reactor.inputTank.fillMb(Ic2FluidStack.create(Ic2Fluids.COOLANT.still(), 1000), false);

    helper.succeedWhen(
        () -> {
          helper.assertTrue(
              port.getReactorInstance() == reactor, "fluid port should find the sealed reactor");
          helper.assertTrue(
              exchanger.getOutputTank().hasExactFluid(Ic2Fluids.COOLANT.still())
                  && exchanger.getOutputTank().getFluidAmount() > 0,
              "heat exchanger should cool hot coolant back into coolant, has "
                  + exchanger.getOutputTank().getFluidAmount()
                  + " mB");
          helper.assertTrue(
              cesu.energy.getEnergy() > 0.0,
              "CESU should bank stirling EU made from reactor heat, has "
                  + cesu.energy.getEnergy());
        });
  }

  // heat beyond the hull's capacity melts the reactor down; containment plating shrinks the blast
  @GameTest(template = EMPTY, batch = "ic2ReactorMeltdown")
  public static void overheatedReactorMeltsDown(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper);

    // 18 containment platings raise capacity to 19000 but damp the explosion to well below one TNT
    for (int y = 0; y < 6; y++) {
      for (int x = 0; x < 3; x++) {
        reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.CONTAINMENT_REACTOR_PLATING));
      }
    }

    reactor.setHeat(100000);

    helper.succeedWhen(() -> helper.assertBlockPresent(Blocks.AIR, REACTOR_POS));
  }

  // a critically hot reactor irradiates nearby entities; regression test for the server crash
  // where the reactor was the first ic2 damage dealer after startup and hurt entities with the
  // still-null Ic2DamageSource.radiation static
  @GameTest(template = EMPTY_LARGE, timeoutTicks = 300)
  public static void hotReactorIrradiatesNearbyEntities(GameTestHelper helper) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);
    // 75% of hull capacity: above the 70% radiation threshold, below the 85% fire threshold
    reactor.setHeat(7500);
    // simulate a freshly started server where no lazy init path has populated the statics yet
    Ic2DamageSource.radiation = null;
    Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, VESSEL_CENTER.above());

    helper.assertTrue(
        ItemArmorHazmat.hazmatAbsorbs(Ic2DamageSource.radiation(helper.getLevel())),
        "hazmat should absorb level-created radiation damage");

    // the radiation roll is 0-3 damage per work tick (one every 20 ticks), so wait for a hit
    helper.succeedWhen(
        () -> {
          helper.assertTrue(
              pig.getHealth() < pig.getMaxHealth(),
              "a reactor at 75% heat should radiation-damage the pig next to it");
          helper.assertTrue(
              Ic2DamageSource.radiation == null,
              "radiation effect must use the current level damage source instead of the static one");
        });
  }

  private static TileEntityNuclearReactorElectric placeReactor(GameTestHelper helper) {
    return placeReactor(helper, REACTOR_POS);
  }

  private static TileEntityNuclearReactorElectric placeReactor(
      GameTestHelper helper, BlockPos pos) {
    helper.setBlock(pos, Ic2Blocks.NUCLEAR_REACTOR);
    return getTe(helper, pos, TileEntityNuclearReactorElectric.class);
  }

  private static void verifyQuadRodLayout(GameTestHelper helper, int rodCount) {
    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);
    for (Direction direction : Direction.values()) {
      helper.setBlock(VESSEL_CENTER.relative(direction), Ic2Blocks.REACTOR_CHAMBER);
    }

    // A chamber relays this signal to the reactor while leaving all six chamber positions filled.
    helper.setBlock(VESSEL_CENTER.offset(-2, 0, 0), Blocks.REDSTONE_BLOCK);

    String[] layout = QuadRodReactorLayouts.forRodCount(rodCount);
    int loadedRods = 0;
    int loadedOverclockedVents = 0;
    int loadedComponentVents = 0;
    for (int y = 0; y < layout.length; y++) {
      helper.assertValueEqual(layout[y].length(), 9, "layout row width");
      for (int x = 0; x < layout[y].length(); x++) {
        switch (layout[y].charAt(x)) {
          case QuadRodReactorLayouts.QUAD_ROD -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.QUAD_URANIUM_FUEL_ROD));
            loadedRods++;
          }
          case QuadRodReactorLayouts.OVERCLOCKED_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.OVERCLOCKED_HEAT_VENT));
            loadedOverclockedVents++;
          }
          case QuadRodReactorLayouts.COMPONENT_VENT -> {
            reactor.reactorSlot.put(x, y, new ItemStack(Ic2Items.COMPONENT_HEAT_VENT));
            loadedComponentVents++;
          }
          case QuadRodReactorLayouts.EMPTY -> {
            // Deliberately empty.
          }
          default -> throw new IllegalArgumentException("unknown reactor layout symbol");
        }
      }
    }

    helper.assertValueEqual(layout.length, 6, "layout row count");
    helper.assertValueEqual(loadedRods, rodCount, "quad rods loaded from layout");
    helper.assertValueEqual(
        loadedOverclockedVents + loadedComponentVents,
        QuadRodReactorLayouts.ventCount(rodCount),
        "minimal vent count");
    ItemReactorHeatStorage overclockedVent =
        (ItemReactorHeatStorage) Ic2Items.OVERCLOCKED_HEAT_VENT;
    int expectedOverclockedVents = loadedOverclockedVents;
    int expectedComponentVents = loadedComponentVents;

    // Let the layout complete about ten reactor work cycles so this checks sustained venting, not
    // just heat acceptance during the first cycle.
    helper.runAtTickTime(
        220,
        () -> {
          int simulatedCycles = 0;
          while (!hasDepletedQuadRod(reactor) && simulatedCycles < 20000) {
            reactor.output = 0.0F;
            reactor.processChambers();
            simulatedCycles++;
          }

          helper.assertTrue(
              hasDepletedQuadRod(reactor),
              "a quad rod should deplete within 20000 simulated work cycles");
          helper.assertValueEqual(reactor.getReactorSize(), 9, "full reactor grid width");
          helper.assertValueEqual(reactor.getHeat(), 0, "quad layout hull heat");

          int overclockedVents = 0;
          int componentVents = 0;
          for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 9; x++) {
              ItemStack stack = reactor.getItemAt(x, y);
              if (stack != null && stack.getItem() == Ic2Items.OVERCLOCKED_HEAT_VENT) {
                overclockedVents++;
                helper.assertTrue(
                    overclockedVent.getCurrentHeat(stack, reactor, x, y)
                        < overclockedVent.getMaxHeat(stack, reactor, x, y),
                    "overclocked heat vent at " + x + "," + y + " should remain intact");
              } else if (stack != null && stack.getItem() == Ic2Items.COMPONENT_HEAT_VENT) {
                componentVents++;
              }
            }
          }

          helper.assertValueEqual(
              overclockedVents, expectedOverclockedVents, "intact overclocked heat vents");
          helper.assertValueEqual(
              componentVents, expectedComponentVents, "intact component heat vents");
          helper.succeed();
        });
  }

  private static boolean hasDepletedQuadRod(TileEntityNuclearReactorElectric reactor) {
    for (int y = 0; y < 6; y++) {
      for (int x = 0; x < 9; x++) {
        ItemStack stack = reactor.getItemAt(x, y);
        if (stack != null && stack.getItem() == Ic2Items.DEPLETED_QUAD_URANIUM_FUEL_ROD) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean hasDepletedFuelRod(TileEntityNuclearReactorElectric reactor) {
    for (int y = 0; y < 6; y++) {
      for (int x = 0; x < 9; x++) {
        ItemStack stack = reactor.getItemAt(x, y);
        if (stack != null
            && (stack.getItem() == Ic2Items.DEPLETED_URANIUM_FUEL_ROD
                || stack.getItem() == Ic2Items.DEPLETED_DUAL_URANIUM_FUEL_ROD
                || stack.getItem() == Ic2Items.DEPLETED_QUAD_URANIUM_FUEL_ROD)) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean hasDepletedMoxFuelRod(TileEntityNuclearReactorElectric reactor) {
    for (int y = 0; y < 6; y++) {
      for (int x = 0; x < 9; x++) {
        ItemStack stack = reactor.getItemAt(x, y);
        if (stack != null
            && (stack.getItem() == Ic2Items.DEPLETED_MOX_FUEL_ROD
                || stack.getItem() == Ic2Items.DEPLETED_DUAL_MOX_FUEL_ROD
                || stack.getItem() == Ic2Items.DEPLETED_QUAD_MOX_FUEL_ROD)) {
          return true;
        }
      }
    }

    return false;
  }

  // hollow 5x5x5 reactor vessel shell around a fully chambered reactor; tests punch ports into the
  // shell afterwards
  private static TileEntityNuclearReactorElectric buildFluidReactor(GameTestHelper helper) {
    for (int x = -2; x <= 2; x++) {
      for (int y = -2; y <= 2; y++) {
        for (int z = -2; z <= 2; z++) {
          if (Math.abs(x) == 2 || Math.abs(y) == 2 || Math.abs(z) == 2) {
            helper.setBlock(VESSEL_CENTER.offset(x, y, z), Ic2Blocks.REACTOR_VESSEL);
          }
        }
      }
    }

    TileEntityNuclearReactorElectric reactor = placeReactor(helper, VESSEL_CENTER);

    for (Direction dir : Direction.values()) {
      helper.setBlock(VESSEL_CENTER.relative(dir), Ic2Blocks.REACTOR_CHAMBER);
    }

    return reactor;
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
