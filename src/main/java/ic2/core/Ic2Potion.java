package ic2.core;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

public class Ic2Potion extends MobEffect {
  public static Ic2Potion radiation;

  public Ic2Potion(MobEffectCategory type, int liquidColor) {
    super(type, liquidColor);
  }

  /**
   * Returns the registry Holder for the radiation effect. In 1.21 the mob-effect system is
   * Holder-based, so effect look-ups/additions require the Holder rather than the raw {@link
   * MobEffect} instance.
   */
  public static Holder<MobEffect> radiationHolder() {
    return BuiltInRegistries.MOB_EFFECT.wrapAsHolder(radiation);
  }

  public boolean applyEffectTick(LivingEntity entity, int amplifier) {
    if (this == radiation) {
      entity.hurt(Ic2DamageSource.radiation(entity.level()), (float) amplifier / 100 + 0.5F);
    }

    return true;
  }

  public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
    if (this == radiation) {
      int rate = 25 >> amplifier;
      return rate > 0 ? duration % rate == 0 : true;
    } else {
      return false;
    }
  }

  public void applyTo(LivingEntity entity, int duration, int amplifier) {
    MobEffectInstance effect = new MobEffectInstance(radiationHolder(), duration, amplifier);
    entity.addEffect(effect);
  }
}
