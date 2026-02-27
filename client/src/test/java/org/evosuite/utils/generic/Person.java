/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.utils.generic;
/**
 * Simple test class of simple setters and getters.
 * Used for testing variable name generation
 */
public class Person {
    String name;
    Long id;
    int age;
    public Person () {
    }
    public Person (String name, Long id) {
        this.name = name;
        this.id = id;
    }
    public void setId(long id) {this.id = id;}
    public void setName(String name) {this.name = name;}
    public void setAge(int actualAge) {this.age = actualAge;}
    public String getName() { return name; }
    public Long getId() { return id; }
    public int getAge() { return age; }

    public boolean isAdult() { return age>=18; }
    public int getFixedId() {return 18;}
}
