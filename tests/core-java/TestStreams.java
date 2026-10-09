// SPDX-License-Identifier: Apache-2.0
import java.util.*;
import java.util.stream.*;
public class TestStreams {
 public static void main(String[] args) {
  List<String> result=Stream.of("a", "b").collect(Collectors.toList());
  if (!result.equals(Arrays.asList("a","b"))) throw new AssertionError(result);
  System.out.println("PASS: boot-class constructor reference in Collectors.toList");
  System.exit(0);
 }
}
