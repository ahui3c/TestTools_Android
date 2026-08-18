package tw.chehu.testtools;

final class LinkItem {
    final String category;
    final String name;
    final String url;
    final String type;
    final String folderName;

    LinkItem(String category, String name, String url, String type, String folderName) {
        this.category = category;
        this.name = name;
        this.url = url;
        this.type = type;
        this.folderName = folderName;
    }
}
