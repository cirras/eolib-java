package dev.cirras.generate.type;

import java.util.Optional;

public class LongType implements BasicType {
  private final String name;
  private final int size;

  public LongType(String name, int size) {
    this.name = name;
    this.size = size;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Optional<Integer> getFixedSize() {
    return Optional.of(size);
  }

  @Override
  public boolean isBounded() {
    return true;
  }
}
