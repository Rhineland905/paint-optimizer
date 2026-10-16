package io.github.rhineland905.paintoptimizer.render;

/** Mixed into {@code TextDisplayEntityRenderState} so submission can see what extraction decided. */
public interface PaintStateHolder {
	PaintBinding paintoptimizer$binding();

	void paintoptimizer$setBinding(PaintBinding binding);
}
