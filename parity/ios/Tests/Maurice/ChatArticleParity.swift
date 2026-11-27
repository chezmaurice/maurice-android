import XCTest

/// A page shared from another app, read into an article card
/// (parity/vectors/maurice/chat-article.json).
@MainActor
final class ChatArticleParity: XCTestCase {
    func testChatArticle() async {
        await Parity.run("maurice", "chat-article") { c in
            let chat = ChatService(session: MauriceFixture.session())
            // The share sheet hands no title when the app sent an address alone.
            let page = SharedPage(url: c.string("url") ?? "", title: c.string("title") ?? "", html: "", selection: "", sharedAt: Date())
            let a = await chat.readSharedArticle(page)
            var card = ChatSupport.articleCard(a)
            card["fenced"] = a.fenced
            return ["sent": ChatSupport.sent(await ChatSupport.log()), "card": card]
        }
    }
}
