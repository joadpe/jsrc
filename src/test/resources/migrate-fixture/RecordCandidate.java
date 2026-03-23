package test;

/**
 * This class has only fields + constructor + getters.
 * Could be a Java 16 record.
 */
public class RecordCandidate {
    private final String name;
    private final int age;
    private final String email;

    public RecordCandidate(String name, int age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
    }

    public String getName() { return name; }
    public int getAge() { return age; }
    public String getEmail() { return email; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecordCandidate that = (RecordCandidate) o;
        return age == that.age
                && name.equals(that.name)
                && email.equals(that.email);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + age;
        result = 31 * result + email.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "RecordCandidate{name=" + name + ", age=" + age + ", email=" + email + "}";
    }
}