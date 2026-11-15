class ObjectContextLengths {
    public static void main(String[] args) {
        new ObjectContextLengths().first();
        new ObjectContextLengths().first();
    }

    void first() {
        new ObjectContextLengths().second();
    }

    void second() {
        new Object();
    }
}
