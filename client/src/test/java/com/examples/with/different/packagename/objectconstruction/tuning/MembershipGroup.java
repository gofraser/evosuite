package com.examples.with.different.packagename.objectconstruction.tuning;

import java.util.LinkedHashSet;
import java.util.Set;

public class MembershipGroup {

    public static class Member {
    }

    private final Set<Member> members = new LinkedHashSet<>();

    public void add(Member member) {
        members.add(member);
    }

    public boolean accepts(Member member) {
        if (!members.contains(member)) {
            return false;
        }
        return true;
    }
}
