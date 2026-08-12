package app.githubble.mobile.search;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Merges multi-query GitHub repository results and ranks repeated semantic matches above one-off hits. */
public final class RepositorySearchRanker {
    private RepositorySearchRanker() {}

    public static final class Hit {
        public final JSONObject repository;
        public int matchedVariants;
        public int bestRank;
        public double score;
        private Hit(JSONObject repository, int rank) {
            this.repository = repository;
            this.matchedVariants = 1;
            this.bestRank = rank;
        }
    }

    public static List<Hit> merge(List<JSONArray> batches, SmartSearchPlanner.Plan plan) {
        LinkedHashMap<String, Hit> unique = new LinkedHashMap<>();
        if (batches != null) {
            for (JSONArray items : batches) {
                if (items == null) continue;
                HashSet<String> seenInBatch = new HashSet<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject repo = items.optJSONObject(i);
                    if (repo == null) continue;
                    String key = repo.optString("full_name", "").toLowerCase(Locale.ROOT);
                    if (key.isEmpty() || !seenInBatch.add(key)) continue;
                    Hit existing = unique.get(key);
                    if (existing == null) unique.put(key, new Hit(repo, i));
                    else {
                        existing.matchedVariants++;
                        existing.bestRank = Math.min(existing.bestRank, i);
                    }
                }
            }
        }
        ArrayList<Hit> hits = new ArrayList<>(unique.values());
        for (Hit hit : hits) hit.score = score(hit, plan == null ? Collections.emptySet() : plan.rankingTerms);
        hits.sort((a, b) -> {
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            return Integer.compare(b.repository.optInt("stargazers_count", 0), a.repository.optInt("stargazers_count", 0));
        });
        return hits;
    }

    private static double score(Hit hit, Set<String> terms) {
        JSONObject repo = hit.repository;
        double score = hit.matchedVariants * 900.0 + 520.0 / (hit.bestRank + 1.0);
        int stars = Math.max(0, repo.optInt("stargazers_count", 0));
        score += Math.min(220.0, Math.log10(stars + 1.0) * 46.0);
        String name = repo.optString("full_name", "").toLowerCase(Locale.ROOT);
        String description = repo.optString("description", "").toLowerCase(Locale.ROOT);
        String language = repo.optString("language", "").toLowerCase(Locale.ROOT);
        for (String term : terms) {
            String needle = term.toLowerCase(Locale.ROOT);
            if (needle.length() < 3) continue;
            if (name.contains(needle)) score += 95.0;
            if (description.contains(needle)) score += 35.0;
            if (language.equals(needle)) score += 55.0;
        }
        String updated = repo.optString("updated_at", "");
        if (updated.length() >= 4) {
            try {
                int year = Integer.parseInt(updated.substring(0, 4));
                int current = Calendar.getInstance().get(Calendar.YEAR);
                if (year >= current) score += 28.0;
                else if (year == current - 1) score += 16.0;
            } catch (Exception ignored) {}
        }
        return score;
    }
}
