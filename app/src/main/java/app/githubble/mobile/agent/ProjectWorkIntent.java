package app.githubble.mobile.agent;

import java.util.Locale;

/** Only positive development imperatives are routed to the *proposal* flow. */
public final class ProjectWorkIntent {
    private ProjectWorkIntent(){}
    public static boolean requestsFileProposal(String text){
        if(text==null)return false;
        String query=text.trim().toLowerCase(Locale.ROOT);
        if(query.length()<7||query.length()>12000)return false;
        if(query.startsWith("/edit ")||query.startsWith("/code "))return true;
        if(query.startsWith("создай исходники")||query.startsWith("создай файлы"))return true;
        if(query.startsWith("как ")||query.startsWith("почему ")
            ||query.startsWith("можно ")||query.startsWith("объясни ")
            ||query.startsWith("покажи ")||query.startsWith("расскажи "))return false;
        String[] verbs={"добавь ","реализуй ","исправь ","доработай ",
            "перепиши ","переделай ","обнови ","внеси изменения ",
            "внеси правки ","поправь ","создай файл ",
            "создай класс ","создай модуль ","создай экран ",
            "создай тесты ","сделай рефакторинг ","отрефакторь "};
        for(String verb:verbs)if(query.startsWith(verb))return true;
        return false;
    }
}
