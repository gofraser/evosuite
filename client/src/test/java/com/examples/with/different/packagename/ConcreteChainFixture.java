/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package com.examples.with.different.packagename;

/**
 * A two-level interface chain used to exercise {@code GenerationContext.withConcreteChain()}: an
 * interface ({@link Outer}) whose only implementation ({@link OuterImpl}) itself takes another
 * interface ({@link Inner}) with its own single implementation ({@link InnerImpl}). Reaching
 * behaviour behind {@code Outer} concretely requires both links of the chain to be built for
 * real, the same shape as 104_vuze's {@code Transport} -&gt; {@code TransportHelperFilter} chain.
 */
public class ConcreteChainFixture {

    // Two abstract methods on purpose: a single-method interface is a functional interface,
    // which keeps its 1.0 mock boost even inside a concrete chain (see VariableResolver's
    // effectiveMockProb comment). This fixture targets the *ordinary* interface/abstract case,
    // whose 0.5 boost is what withConcreteChain() suppresses.
    public interface Inner {
        int value();

        String label();
    }

    public static final class InnerImpl implements Inner {
        public InnerImpl() {
        }

        @Override
        public int value() {
            return 42;
        }

        @Override
        public String label() {
            return "inner";
        }
    }

    public interface Outer {
        Inner getInner();
    }

    public static final class OuterImpl implements Outer {
        private final Inner inner;

        public OuterImpl(Inner inner) {
            this.inner = inner;
        }

        @Override
        public Inner getInner() {
            return inner;
        }
    }
}
