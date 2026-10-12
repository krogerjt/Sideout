import CoreData

/// A favorite team as the rest of the app sees it, detached from Core Data.
struct FavoriteTeam: Hashable, Identifiable {
    let slug: String
    let name: String
    let division: String
    var id: String { slug }
}

@objc(FavoriteTeamRecord)
final class FavoriteTeamRecord: NSManagedObject {
    @NSManaged var slug: String
    @NSManaged var name: String
    @NSManaged var division: String
    @NSManaged var addedAt: Date
}

/// Favorite teams saved on this iPhone with Core Data. The model is built in code so XcodeGen needs no .xcdatamodeld bundle.
@MainActor
final class FavoritesStore {
    static let model: NSManagedObjectModel = {
        func attribute(_ name: String, _ type: NSAttributeType) -> NSAttributeDescription {
            let attribute = NSAttributeDescription()
            attribute.name = name
            attribute.attributeType = type
            attribute.isOptional = false
            return attribute
        }
        let entity = NSEntityDescription()
        entity.name = "FavoriteTeam"
        entity.managedObjectClassName = NSStringFromClass(FavoriteTeamRecord.self)
        entity.properties = [attribute("slug", .stringAttributeType), attribute("name", .stringAttributeType),
                             attribute("division", .stringAttributeType), attribute("addedAt", .dateAttributeType)]
        entity.uniquenessConstraints = [["slug"]]
        let model = NSManagedObjectModel()
        model.entities = [entity]
        return model
    }()

    private let container: NSPersistentContainer
    private var context: NSManagedObjectContext { container.viewContext }

    init(inMemory: Bool = false) {
        container = NSPersistentContainer(name: "Favorites", managedObjectModel: Self.model)
        if inMemory { container.persistentStoreDescriptions.first?.url = URL(fileURLWithPath: "/dev/null") }
        container.loadPersistentStores { _, error in
            if let error { print("Couldn't open favorites: \(error.localizedDescription)") }
        }
        context.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
    }

    func all() -> [FavoriteTeam] {
        let request = NSFetchRequest<FavoriteTeamRecord>(entityName: "FavoriteTeam")
        request.sortDescriptors = [NSSortDescriptor(key: "name", ascending: true)]
        return ((try? context.fetch(request)) ?? []).map { FavoriteTeam(slug: $0.slug, name: $0.name, division: $0.division) }
    }

    /// Adds the team, or refreshes its name and division if it is already saved.
    func save(_ team: FavoriteTeam) {
        let record = record(team.slug) ?? {
            let record = FavoriteTeamRecord(entity: Self.model.entitiesByName["FavoriteTeam"]!, insertInto: context)
            record.slug = team.slug
            record.addedAt = Date()
            return record
        }()
        record.name = team.name
        record.division = team.division
        commit()
    }

    func remove(_ slug: String) {
        guard let record = record(slug) else { return }
        context.delete(record)
        commit()
    }

    /// Fills in teams imported by slug alone once a scoreboard shows their real name and division.
    func resolve(_ teams: [(VolleyballTeam, String)]) -> Bool {
        var changed = false
        for (team, division) in teams {
            guard let record = record(team.slug), record.name == record.slug else { continue }
            record.name = team.name
            record.division = division
            changed = true
        }
        commit()
        return changed
    }

    /// Earlier builds kept only team slugs in UserDefaults. They move here once, named by slug until a scoreboard shows the team.
    func importLegacy(_ defaults: UserDefaults = .standard) {
        guard let slugs = defaults.stringArray(forKey: "favorites") else { return }
        for slug in slugs where record(slug) == nil { save(FavoriteTeam(slug: slug, name: slug, division: "d1")) }
        defaults.removeObject(forKey: "favorites")
    }

    private func record(_ slug: String) -> FavoriteTeamRecord? {
        let request = NSFetchRequest<FavoriteTeamRecord>(entityName: "FavoriteTeam")
        request.predicate = NSPredicate(format: "slug == %@", slug)
        request.fetchLimit = 1
        return try? context.fetch(request).first
    }
    private func commit() {
        guard context.hasChanges else { return }
        do { try context.save() } catch { context.rollback(); print("Couldn't save favorites: \(error.localizedDescription)") }
    }
}
